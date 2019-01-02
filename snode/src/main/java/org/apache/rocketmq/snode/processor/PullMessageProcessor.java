package org.apache.rocketmq.snode.processor;/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import io.netty.channel.ChannelHandlerContext;
import java.util.concurrent.CompletableFuture;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.help.FAQUrl;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.common.protocol.header.PullMessageRequestHeader;
import org.apache.rocketmq.common.protocol.header.PullMessageResponseHeader;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.common.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.common.subscription.SubscriptionGroupConfig;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.RemotingChannel;
import org.apache.rocketmq.remoting.RequestProcessor;
import org.apache.rocketmq.remoting.exception.RemotingCommandException;
import org.apache.rocketmq.remoting.exception.RemotingConnectException;
import org.apache.rocketmq.remoting.exception.RemotingSendRequestException;
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException;
import org.apache.rocketmq.remoting.netty.NettyChannelHandlerContextImpl;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.snode.SnodeController;
import org.apache.rocketmq.snode.client.ConsumerGroupInfo;

public class PullMessageProcessor implements RequestProcessor {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.SNODE_LOGGER_NAME);

    private final SnodeController snodeController;

    public PullMessageProcessor(SnodeController snodeController) {
        this.snodeController = snodeController;
    }

    @Override
    public RemotingCommand processRequest(RemotingChannel remotingChannel,
        RemotingCommand request) throws RemotingCommandException {
        NettyChannelHandlerContextImpl nettyChannelHandlerContext = (NettyChannelHandlerContextImpl) remotingChannel;
        ChannelHandlerContext ctx = nettyChannelHandlerContext.getChannelHandlerContext();

        RemotingCommand response = RemotingCommand.createResponseCommand(PullMessageResponseHeader.class);

        final PullMessageRequestHeader requestHeader =
            (PullMessageRequestHeader) request.decodeCommandCustomHeader(PullMessageRequestHeader.class);

        ConsumerGroupInfo consumerGroupInfo = snodeController.getConsumerManager().getConsumerGroupInfo(requestHeader.getConsumerGroup());

        SubscriptionGroupConfig subscriptionGroupConfig =
            this.snodeController.getSubscriptionGroupManager().findSubscriptionGroupConfig(requestHeader.getConsumerGroup());
        if (null == subscriptionGroupConfig) {
            response.setCode(ResponseCode.SUBSCRIPTION_GROUP_NOT_EXIST);
            response.setRemark(String.format("Subscription group [%s] does not exist, %s", requestHeader.getConsumerGroup(), FAQUrl.suggestTodo(FAQUrl.SUBSCRIPTION_GROUP_NOT_EXIST)));
            return response;
        }

        if (!subscriptionGroupConfig.isConsumeEnable()) {
            response.setCode(ResponseCode.NO_PERMISSION);
            response.setRemark("Subscription group no permission, " + requestHeader.getConsumerGroup());
            return response;
        }

        if (!subscriptionGroupConfig.isConsumeBroadcastEnable()
            && consumerGroupInfo.getMessageModel() == MessageModel.BROADCASTING) {
            response.setCode(ResponseCode.NO_PERMISSION);
            response.setRemark("The consumer group[" + requestHeader.getConsumerGroup() + "] can not consume by broadcast way");
            return response;
        }

        SubscriptionData subscriptionData = consumerGroupInfo.findSubscriptionData(requestHeader.getTopic());
        if (null == subscriptionData) {
            log.warn("The consumer's subscription not exist, group: {}, topic:{}", requestHeader.getConsumerGroup(), requestHeader.getTopic());
            response.setCode(ResponseCode.SUBSCRIPTION_NOT_EXIST);
            response.setRemark("The consumer's subscription not exist" + FAQUrl.suggestTodo(FAQUrl.SAME_GROUP_DIFFERENT_TOPIC));
            return response;
        }

        if (subscriptionData.getSubVersion() < requestHeader.getSubVersion()) {
            log.warn("The broker's subscription is not latest, group: {} {}", requestHeader.getConsumerGroup(),
                subscriptionData.getSubString());
            response.setCode(ResponseCode.SUBSCRIPTION_NOT_LATEST);
            response.setRemark("The consumer's subscription not latest");
            return response;
        }

        CompletableFuture<RemotingCommand> responseFuture = snodeController.getEnodeService().pullMessage(ctx, request);
        responseFuture.whenComplete((data, ex) -> {
            if (ex == null) {
                this.snodeController.getSnodeServer().sendResponse(remotingChannel, data);
            } else {
                log.error("Pull message error: {}", ex);
            }
        });
        return null;
    }

    @Override
    public boolean rejectRequest() {
        return false;
    }
}