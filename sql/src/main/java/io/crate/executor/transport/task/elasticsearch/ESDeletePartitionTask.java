/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.executor.transport.task.elasticsearch;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.util.concurrent.ListenableFuture;
import io.crate.action.sql.ResultReceiver;
import io.crate.core.collections.Row;
import io.crate.core.collections.Row1;
import io.crate.executor.JobTask;
import io.crate.executor.TaskResult;
import io.crate.executor.transport.OneRowActionListener;
import io.crate.operation.projectors.RowReceiver;
import io.crate.planner.node.ddl.ESDeletePartition;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.admin.indices.delete.TransportDeleteIndexAction;
import org.elasticsearch.action.support.IndicesOptions;

import java.util.List;

public class ESDeletePartitionTask extends JobTask {

    private static final Function<Object, Row> TO_UNKNOWN_COUNT_ROW = Functions.<Row>constant(new Row1(-1L));;

    private final TransportDeleteIndexAction transport;
    private final DeleteIndexRequest request;

    @Override
    public void execute(RowReceiver rowReceiver) {
        OneRowActionListener<DeleteIndexResponse> actionListener = new OneRowActionListener<>(rowReceiver, TO_UNKNOWN_COUNT_ROW);
        transport.execute(request, actionListener);
    }

    @Override
    public List<? extends ListenableFuture<TaskResult>> executeBulk() {
        throw new UnsupportedOperationException("delete partition task cannot be executed as bulk operation");
    }

    public ESDeletePartitionTask(ESDeletePartition esDeletePartition, TransportDeleteIndexAction transport) {
        super(esDeletePartition.jobId());
        this.transport = transport;
        this.request = new DeleteIndexRequest(esDeletePartition.indices());

        /**
         * table is partitioned, in case of concurrent "delete from partitions"
         * it could be that some partitions are already deleted,
         * so ignore it if some are missing
         */
        this.request.indicesOptions(IndicesOptions.lenientExpandOpen());
    }
}
