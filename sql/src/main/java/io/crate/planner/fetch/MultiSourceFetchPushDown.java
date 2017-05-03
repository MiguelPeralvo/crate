/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.planner.fetch;

import io.crate.analyze.MultiSourceSelect;
import io.crate.analyze.relations.AnalyzedRelation;
import io.crate.analyze.relations.DocTableRelation;
import io.crate.analyze.relations.QueriedDocTable;
import io.crate.analyze.relations.QueriedRelation;
import io.crate.analyze.symbol.*;
import io.crate.metadata.DocReferences;
import io.crate.metadata.Reference;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.TableIdent;
import io.crate.metadata.doc.DocSysColumns;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.planner.node.fetch.FetchSource;
import io.crate.sql.tree.QualifiedName;
import io.crate.types.DataTypes;

import java.util.*;

class MultiSourceFetchPushDown {

    private final MultiSourceSelect statement;

    private List<Symbol> remainingOutputs;
    private Map<TableIdent, FetchSource> fetchSources;

    MultiSourceFetchPushDown(MultiSourceSelect statement) {
        this.statement = statement;
        this.fetchSources = new HashMap<>(statement.sources().size());
    }

    Map<TableIdent, FetchSource> fetchSources() {
        return fetchSources;
    }

    List<Symbol> remainingOutputs() {
        return remainingOutputs;
    }

    void process() {
        remainingOutputs = statement.querySpec().outputs();
        statement.querySpec().outputs(new ArrayList<>());

        HashMap<Symbol, Symbol> topLevelOutputMap = new HashMap<>(statement.canBeFetched().size());
        HashMap<Symbol, Symbol> mssOutputMap = new HashMap<>(statement.querySpec().outputs().size() + 2);

        ArrayList<Symbol> mssOutputs = new ArrayList<>(
            statement.sources().size() + statement.requiredForQuery().size());

        for (Map.Entry<QualifiedName, AnalyzedRelation> entry : statement.sources().entrySet()) {
            QueriedRelation relation = (QueriedRelation) entry.getValue();
            if (!(relation instanceof QueriedDocTable)) {
                int index = 0;
                for (Symbol output : relation.querySpec().outputs()) {
                    RelationColumn rc = new RelationColumn(entry.getKey(), index++, output.valueType());
                    mssOutputs.add(rc);
                    mssOutputMap.put(output, rc);
                    topLevelOutputMap.put(output, new InputColumn(mssOutputs.size() - 1, output.valueType()));
                }
                continue;
            }

            DocTableRelation tableRelation = ((QueriedDocTable) relation).tableRelation();
            DocTableInfo tableInfo = tableRelation.tableInfo();
            HashSet<Field> canBeFetched = forSubRelation(statement.canBeFetched(), relation, tableRelation);
            if (!canBeFetched.isEmpty()) {
                RelationColumn fetchIdColumn = new RelationColumn(entry.getKey(), 0, DataTypes.LONG);
                mssOutputs.add(fetchIdColumn);
                InputColumn fetchIdInput = new InputColumn(mssOutputs.size() - 1);

                ArrayList<Symbol> qtOutputs = new ArrayList<>(
                    relation.querySpec().outputs().size() - canBeFetched.size() + 1);
                Reference fetchId = tableInfo.getReference(DocSysColumns.FETCHID);
                qtOutputs.add(fetchId);

                for (Symbol output : relation.querySpec().outputs()) {
                    if (!(output instanceof Field) || !canBeFetched.contains(output)) {
                        qtOutputs.add(output);
                        RelationColumn rc = new RelationColumn(entry.getKey(),
                            qtOutputs.size() - 1, output.valueType());
                        mssOutputs.add(rc);
                        mssOutputMap.put(output, rc);
                        topLevelOutputMap.put(output, new InputColumn(mssOutputs.size() - 1, output.valueType()));
                    }
                }
                for (Field field : canBeFetched) {
                    FetchReference fr = new FetchReference(
                        fetchIdInput, DocReferences.toSourceLookup(tableRelation.resolveField(tableRelation.getField(field.path()))));
                    allocateFetchedReference(fr, tableInfo.partitionedByColumns());
                    topLevelOutputMap.put(field, fr);
                }
                relation.querySpec().outputs(qtOutputs);
            } else {
                int index = 0;
                for (Symbol output : relation.querySpec().outputs()) {
                    RelationColumn rc = new RelationColumn(entry.getKey(), index++, output.valueType());
                    mssOutputs.add(rc);
                    mssOutputMap.put(output, rc);
                    topLevelOutputMap.put(output, new InputColumn(mssOutputs.size() - 1, output.valueType()));
                }
            }
        }

        statement.querySpec().outputs(mssOutputs);
        MappingSymbolVisitor.inPlace().processInplace(remainingOutputs, topLevelOutputMap);
        if (statement.querySpec().orderBy().isPresent()) {
            MappingSymbolVisitor.inPlace().processInplace(statement.querySpec().orderBy().get().orderBySymbols(), mssOutputMap);
        }
    }

    private static HashSet<Field> forSubRelation(Set<Field> fields, AnalyzedRelation rel, DocTableRelation tableRelation) {
        HashSet<Field> filteredFields = new HashSet<>();
        for (Field field : fields) {
            if (field.relation() == rel) {
                filteredFields.add(tableRelation.getField(field.path()));
            }
        }
        return filteredFields;
    }

    private void allocateFetchedReference(FetchReference fr, List<Reference> partitionedByColumns) {
        FetchSource fs = fetchSources.get(fr.ref().ident().tableIdent());
        if (fs == null) {
            fs = new FetchSource(partitionedByColumns);
            fetchSources.put(fr.ref().ident().tableIdent(), fs);
        }
        fs.fetchIdCols().add((InputColumn) fr.fetchId());
        if (fr.ref().granularity() == RowGranularity.DOC) {
            fs.references().add(fr.ref());
        }
    }
}
