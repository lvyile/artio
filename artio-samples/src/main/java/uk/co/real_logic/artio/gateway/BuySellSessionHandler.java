/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.gateway;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.decoder.ExecutionReportDecoder;
import uk.co.real_logic.artio.decoder.HeartbeatDecoder;
import uk.co.real_logic.artio.decoder.LogonDecoder;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;

/**
 * BuySell Session Handler
 * 
 * 这个类负责处理每个会话（Session）接收到的 FIX 消息。
 * 每个客户端连接都会创建一个 Session，每个 Session 都有一个 SessionHandler。
 * 
 * 主要功能：
 * 1. 接收并记录所有 FIX 消息
 * 2. 处理 Logon、Heartbeat、ExecutionReport 等消息
 * 3. 记录消息日志用于调试
 */
public class BuySellSessionHandler implements SessionHandler {
    private final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer();

    // 消息解码器（用于解析 FIX 消息）
    private final LogonDecoder logonDecoder = new LogonDecoder();
    private final HeartbeatDecoder heartbeatDecoder = new HeartbeatDecoder();
    private final ExecutionReportDecoder executionReportDecoder = new ExecutionReportDecoder();

    public BuySellSessionHandler(final Session session) {
        // Session 对象可以在这里保存，如果需要的话
        // 目前我们主要通过 onMessage 方法的 session 参数访问
    }

    /**
     * 当收到消息时调用
     * 
     * @param buffer        消息缓冲区
     * @param offset        消息在缓冲区中的偏移量
     * @param length        消息长度
     * @param libraryId     库 ID
     * @param session       会话对象
     * @param sequenceIndex 序列号索引
     * @param messageType   消息类型（FIX 消息类型，如 'A'=Logon, '0'=Heartbeat,
     *                      '8'=ExecutionReport）
     * @param timestampInNs 时间戳（纳秒）
     * @param position      位置
     * @param messageInfo   消息信息
     * @return Action.CONTINUE 表示继续处理，Action.ABORT 表示需要重试
     */
    @Override
    public Action onMessage(
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final int libraryId,
            final Session session,
            final int sequenceIndex,
            final long messageType,
            final long timestampInNs,
            final long position,
            final OnMessageInfo messageInfo) {
        // 将消息缓冲区包装为 ASCII 缓冲区，便于读取
        asciiBuffer.wrap(buffer, offset, length);

        // 根据消息类型处理不同的消息
        if (messageType == LogonDecoder.MESSAGE_TYPE) {
            handleLogon();
        } else if (messageType == HeartbeatDecoder.MESSAGE_TYPE) {
            handleHeartbeat();
        } else if (messageType == ExecutionReportDecoder.MESSAGE_TYPE) {
            handleExecutionReport();
        } else {
            // 其他类型的消息
            System.out.println("📨 收到消息类型: " + (char) messageType +
                    " (序列号: " + sequenceIndex + ")");
        }

        return CONTINUE;
    }

    /**
     * 处理 Logon 消息（35=A）
     */
    private void handleLogon() {
        logonDecoder.decode(asciiBuffer, 0, asciiBuffer.capacity());
        System.out.println("✅ Logon 消息已接收");
        System.out.println("   HeartBtInt: " + logonDecoder.heartBtInt());
        System.out.println("   EncryptMethod: " + logonDecoder.encryptMethod());
    }

    /**
     * 处理 Heartbeat 消息（35=0）
     */
    private void handleHeartbeat() {
        heartbeatDecoder.decode(asciiBuffer, 0, asciiBuffer.capacity());
        // 心跳消息通常不需要特殊处理，静默接收即可
        // 如果需要响应 TestRequest，可以在这里处理
    }

    /**
     * 处理 ExecutionReport 消息（35=8）
     * 这是客户端发送的执行回报消息
     */
    private void handleExecutionReport() {
        executionReportDecoder.decode(asciiBuffer, 0, asciiBuffer.capacity());

        // 提取关键字段
        final String orderId = executionReportDecoder.orderIDAsString();
        final String execId = executionReportDecoder.execIDAsString();

        System.out.println("📊 ExecutionReport 已接收:");
        System.out.println("   OrderID: " + orderId);
        System.out.println("   ExecID: " + execId);
    }

    /**
     * 会话启动时调用
     */
    @Override
    public void onSessionStart(final Session session) {
        System.out.println("🚀 会话已启动: " + session.compositeKey());
    }

    /**
     * 超时处理
     */
    @Override
    public void onTimeout(final int libraryId, final Session session) {
        System.out.println("⏰ 会话超时: " + session.compositeKey());
    }

    /**
     * 慢速状态变化
     */
    @Override
    public void onSlowStatus(final int libraryId, final Session session, final boolean hasBecomeSlow) {
        if (hasBecomeSlow) {
            System.out.println("⚠️  会话变慢: " + session.compositeKey());
        }
    }

    /**
     * 断开连接时调用
     */
    @Override
    public Action onDisconnect(
            final int libraryId,
            final Session session,
            final DisconnectReason reason) {
        System.out.println("👋 会话断开: " + session.compositeKey());
        System.out.println("   原因: " + reason);
        return CONTINUE;
    }
}
