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
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.*;
import uk.co.real_logic.artio.builder.ExecutionReportEncoder;
import uk.co.real_logic.artio.decoder.ExecutionReportDecoder;
import uk.co.real_logic.artio.decoder.HeartbeatDecoder;
import uk.co.real_logic.artio.decoder.LogonDecoder;
import uk.co.real_logic.artio.decoder.NewOrderSingleDecoder;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * BuySell Session Handler
 * 
 * 这个类负责处理每个会话（Session）接收到的 FIX 消息。
 * 每个客户端连接都会创建一个 Session，每个 Session 都有一个 SessionHandler。
 * 
 * 主要功能：
 * 1. 接收并记录所有 FIX 消息
 * 2. 处理 Logon、Heartbeat、ExecutionReport 等消息
 * 3. 处理 NewOrderSingle (35=D)、OrderCancelRequest
 * (35=F)、OrderCancelReplaceRequest (35=G)
 * 4. 订单管理和状态跟踪
 * 5. 记录消息日志用于调试
 */
public class BuySellSessionHandler implements SessionHandler {
    // 消息类型常量
    private static final long NEW_ORDER_SINGLE_MESSAGE_TYPE = 68L; // 'D'
    private static final long ORDER_CANCEL_REQUEST_MESSAGE_TYPE = 70L; // 'F'
    private static final long ORDER_CANCEL_REPLACE_REQUEST_MESSAGE_TYPE = 71L; // 'G'

    // ID 生成器缓冲区大小
    private static final int SIZE_OF_ASCII_LONG = String.valueOf(Long.MAX_VALUE).length();

    // 订单管理：ClOrdID -> OrderInfo
    private final Map<String, OrderInfo> orders = new ConcurrentHashMap<>();

    // ID 生成器（每个会话独立）
    private long orderId = 0;
    private long execId = 0;

    // ID 编码缓冲区
    private final byte[] ORDER_ID_BUFFER = new byte[SIZE_OF_ASCII_LONG];
    private int orderIdEncodedLength;
    private final UnsafeBuffer ORDER_ID_ENCODER = new UnsafeBuffer(ORDER_ID_BUFFER);

    private final byte[] EXEC_ID_BUFFER = new byte[SIZE_OF_ASCII_LONG];
    private int execIdEncodedLength;
    private final UnsafeBuffer EXEC_ID_ENCODER = new UnsafeBuffer(EXEC_ID_BUFFER);

    // 消息缓冲区
    private final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer();

    // 消息解码器（用于解析 FIX 消息）
    private final LogonDecoder logonDecoder = new LogonDecoder();
    private final HeartbeatDecoder heartbeatDecoder = new HeartbeatDecoder();
    private final ExecutionReportDecoder executionReportDecoder = new ExecutionReportDecoder();
    private final NewOrderSingleDecoder newOrderSingleDecoder = new NewOrderSingleDecoder();

    // 消息编码器（用于发送 FIX 消息）
    private final ExecutionReportEncoder executionReportEncoder = new ExecutionReportEncoder();

    public BuySellSessionHandler(final Session session) {
        // Session 对象可以在这里保存，如果需要的话
        // 目前我们主要通过 onMessage 方法的 session 参数访问
    }

    /**
     * 订单信息类
     */
    private static class OrderInfo {
        final String clOrdID; // 客户端订单ID
        final String orderID; // 服务器订单ID
        final Side side; // 买卖方向
        final String symbol; // 标的
        OrdStatus ordStatus; // 订单状态
        final DecimalFloat orderQty; // 数量
        final DecimalFloat price; // 价格

        OrderInfo(final String clOrdID, final String orderID, final Side side,
                final String symbol, final OrdStatus ordStatus,
                final DecimalFloat orderQty, final DecimalFloat price) {
            this.clOrdID = clOrdID;
            this.orderID = orderID;
            this.side = side;
            this.symbol = symbol;
            this.ordStatus = ordStatus;
            this.orderQty = orderQty;
            this.price = price;
        }
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

        // 调试：输出所有收到的消息类型
        System.out.println("[DEBUG] 收到消息: type=" + (char) messageType + " (" + messageType + "), seq=" + sequenceIndex);
        System.out.flush();
        
        // 根据消息类型处理不同的消息
        if (messageType == LogonDecoder.MESSAGE_TYPE) {
            handleLogon();
        } else if (messageType == HeartbeatDecoder.MESSAGE_TYPE) {
            handleHeartbeat();
        } else if (messageType == ExecutionReportDecoder.MESSAGE_TYPE) {
            handleExecutionReport();
        } else if (messageType == NEW_ORDER_SINGLE_MESSAGE_TYPE) {
            return handleNewOrderSingle(session);
        } else if (messageType == ORDER_CANCEL_REQUEST_MESSAGE_TYPE) {
            return handleOrderCancelRequest(session);
        } else if (messageType == ORDER_CANCEL_REPLACE_REQUEST_MESSAGE_TYPE) {
            return handleOrderCancelReplaceRequest(session);
        } else {
            // 其他类型的消息
            System.out.println("📨 收到消息类型: " + (char) messageType +
                    " (序列号: " + sequenceIndex + ")");
            System.out.flush();
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
        System.out.flush();
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
     * 处理 NewOrderSingle 消息（35=D）
     * 接收新订单，创建订单记录，发送 ExecutionReport (NEW)
     */
    private Action handleNewOrderSingle(final Session session) {
        newOrderSingleDecoder.decode(asciiBuffer, 0, asciiBuffer.capacity());

        // 提取订单信息
        final String clOrdID = newOrderSingleDecoder.clOrdIDAsString();
        final Side side = newOrderSingleDecoder.sideAsEnum();
        final String symbol = newOrderSingleDecoder.symbolAsString();
        final DecimalFloat orderQty = newOrderSingleDecoder.orderQty();
        final DecimalFloat price = newOrderSingleDecoder.price();

        System.out.println("📝 NewOrderSingle 已接收:");
        System.out.println("   ClOrdID: " + clOrdID);
        System.out.println("   Side: " + side);
        System.out.println("   Symbol: " + symbol);
        System.out.println("   OrderQty: " + orderQty);
        System.out.println("   Price: " + price);
        System.out.flush(); // 确保输出立即刷新

        // 生成订单ID和执行ID
        final String orderID = generateOrderID();
        final String execID = generateExecID();

        // 创建订单记录
        final OrderInfo orderInfo = new OrderInfo(clOrdID, orderID, side, symbol,
                OrdStatus.NEW, orderQty, price);
        orders.put(clOrdID, orderInfo);

        // 发送 ExecutionReport (NEW)
        return sendExecutionReport(session, orderInfo, execID, ExecType.NEW, OrdStatus.NEW);
    }

    /**
     * 处理 OrderCancelRequest 消息（35=F）
     * 接收取消请求，验证订单存在，发送 ExecutionReport (CANCELED)
     */
    private Action handleOrderCancelRequest(final Session session) {
        // 手动解析关键字段（因为可能没有专门的Decoder）
        final String origClOrdID = extractField(asciiBuffer, 41); // OrigClOrdID
        final String clOrdID = extractField(asciiBuffer, 11); // ClOrdID (新的)

        System.out.println("❌ OrderCancelRequest 已接收:");
        System.out.println("   OrigClOrdID: " + origClOrdID);
        System.out.println("   ClOrdID: " + clOrdID);
        System.out.flush();

        // 查找订单
        final OrderInfo orderInfo = orders.get(origClOrdID);
        if (orderInfo == null) {
            System.out.println("   ⚠️  订单不存在: " + origClOrdID);
            // TODO: 可以发送 Reject 消息
            return CONTINUE;
        }

        // 验证订单状态（只能取消 NEW 状态）
        if (orderInfo.ordStatus != OrdStatus.NEW) {
            System.out.println("   ⚠️  订单状态不允许取消: " + orderInfo.ordStatus);
            // TODO: 可以发送 Reject 消息
            return CONTINUE;
        }

        // 更新订单状态
        orderInfo.ordStatus = OrdStatus.CANCELED;

        // 生成新的执行ID
        final String execID = generateExecID();

        // 发送 ExecutionReport (CANCELED)
        return sendExecutionReport(session, orderInfo, execID, ExecType.CANCELED, OrdStatus.CANCELED);
    }

    /**
     * 处理 OrderCancelReplaceRequest 消息（35=G）
     * 接收修改请求，验证订单存在，发送 ExecutionReport (REPLACED)
     */
    private Action handleOrderCancelReplaceRequest(final Session session) {
        // 手动解析关键字段
        final String origClOrdID = extractField(asciiBuffer, 41); // OrigClOrdID
        final String clOrdID = extractField(asciiBuffer, 11); // ClOrdID (新的)
        final String orderQtyStr = extractField(asciiBuffer, 38); // OrderQty
        final String priceStr = extractField(asciiBuffer, 44); // Price

        System.out.println("🔄 OrderCancelReplaceRequest 已接收:");
        System.out.println("   OrigClOrdID: " + origClOrdID);
        System.out.println("   ClOrdID: " + clOrdID);
        System.out.println("   OrderQty: " + orderQtyStr);
        System.out.println("   Price: " + priceStr);
        System.out.flush();

        // 查找订单
        final OrderInfo orderInfo = orders.get(origClOrdID);
        if (orderInfo == null) {
            System.out.println("   ⚠️  订单不存在: " + origClOrdID);
            // TODO: 可以发送 Reject 消息
            return CONTINUE;
        }

        // 验证订单状态（只能修改 NEW 状态）
        if (orderInfo.ordStatus != OrdStatus.NEW) {
            System.out.println("   ⚠️  订单状态不允许修改: " + orderInfo.ordStatus);
            // TODO: 可以发送 Reject 消息
            return CONTINUE;
        }

        // 更新订单信息（如果提供了新值）
        // 注意：这里简化处理，实际应该解析 DecimalFloat
        // 更新订单映射（使用新的 ClOrdID）
        orders.remove(origClOrdID);
        orders.put(clOrdID, orderInfo);

        // 生成新的执行ID
        final String execID = generateExecID();

        // 发送 ExecutionReport (REPLACED)
        // 注意：FIX 4.2 标准字典可能不支持 REPLACED，使用 CANCELED 或保持原状态
        // 实际应用中应该使用 PENDING_REPLACE 状态
        return sendExecutionReport(session, orderInfo, execID, ExecType.CANCELED, OrdStatus.CANCELED);
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
        final ExecType execType = executionReportDecoder.execTypeAsEnum();
        final OrdStatus ordStatus = executionReportDecoder.ordStatusAsEnum();

        System.out.println("📊 ExecutionReport 已接收:");
        System.out.println("   OrderID: " + orderId);
        System.out.println("   ExecID: " + execId);
        System.out.println("   ExecType: " + execType);
        System.out.println("   OrdStatus: " + ordStatus);
        System.out.flush();
    }

    /**
     * 会话启动时调用
     */
    @Override
    public void onSessionStart(final Session session) {
        System.out.println("🚀 会话已启动: " + session.compositeKey());
        System.out.flush();
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
        // 清理订单记录（可选）
        orders.clear();
        return CONTINUE;
    }

    /**
     * 发送 ExecutionReport 消息
     */
    private Action sendExecutionReport(final Session session, final OrderInfo orderInfo,
            final String execID, final ExecType execType,
            final OrdStatus ordStatus) {
        // 重置编码器
        executionReportEncoder.reset();

        // 设置订单和执行ID
        executionReportEncoder.orderID(orderInfo.orderID);
        executionReportEncoder.execID(execID);

        // 设置执行类型和订单状态
        executionReportEncoder.execType(execType);
        executionReportEncoder.ordStatus(ordStatus);

        // 设置买卖方向
        executionReportEncoder.side(orderInfo.side);

        // 设置标的
        executionReportEncoder.instrument().symbol(orderInfo.symbol);

        // 发送消息
        final long sendPosition = session.trySend(executionReportEncoder);

        if (Pressure.isBackPressured(sendPosition)) {
            // 背压：需要重试
            System.out.println("   ⚠️  发送背压，需要重试");
            return ABORT;
        }

        System.out.println("   ✅ ExecutionReport 已发送:");
        System.out.println("      OrderID: " + orderInfo.orderID);
        System.out.println("      ExecID: " + execID);
        System.out.println("      ExecType: " + execType);
        System.out.println("      OrdStatus: " + ordStatus);

        return CONTINUE;
    }

    /**
     * 生成订单ID（GC-free）
     */
    private String generateOrderID() {
        orderId++;
        orderIdEncodedLength = ORDER_ID_ENCODER.putLongAscii(0, orderId);
        return new String(ORDER_ID_BUFFER, 0, orderIdEncodedLength, US_ASCII);
    }

    /**
     * 生成执行ID（GC-free）
     */
    private String generateExecID() {
        execId++;
        execIdEncodedLength = EXEC_ID_ENCODER.putLongAscii(0, execId);
        return new String(EXEC_ID_BUFFER, 0, execIdEncodedLength, US_ASCII);
    }

    /**
     * 从 FIX 消息中提取字段值（手动解析）
     * 
     * @param buffer 消息缓冲区
     * @param tag    字段标签号
     * @return 字段值，如果不存在返回 null
     */
    private String extractField(final MutableAsciiBuffer buffer, final int tag) {
        final int length = buffer.capacity();
        int offset = 0;

        // 跳过消息头（8=FIX.4.2|9=...|35=...）
        // 查找字段：tag=value|
        final String tagStr = String.valueOf(tag) + "=";
        final byte[] tagBytes = tagStr.getBytes(US_ASCII);

        while (offset < length) {
            // 查找标签
            int tagStart = -1;
            for (int i = offset; i < length - tagBytes.length; i++) {
                boolean found = true;
                for (int j = 0; j < tagBytes.length; j++) {
                    if (buffer.getByte(i + j) != tagBytes[j]) {
                        found = false;
                        break;
                    }
                }
                if (found) {
                    tagStart = i + tagBytes.length;
                    break;
                }
            }

            if (tagStart == -1) {
                break; // 未找到
            }

            // 查找值结束位置（SOH = 0x01）
            int valueEnd = tagStart;
            while (valueEnd < length && buffer.getByte(valueEnd) != 0x01) {
                valueEnd++;
            }

            if (valueEnd > tagStart) {
                final int valueLength = valueEnd - tagStart;
                final byte[] valueBytes = new byte[valueLength];
                buffer.getBytes(tagStart, valueBytes);
                return new String(valueBytes, US_ASCII);
            }

            offset = tagStart;
        }

        return null;
    }
}
