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

import org.agrona.concurrent.Agent;
import uk.co.real_logic.artio.library.AcquiringSessionExistsHandler;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.session.Session;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static java.util.Collections.singletonList;

/**
 * BuySell Gateway Agent
 * 
 * Agent 是 Artio 中的核心概念，它负责：
 * 1. 连接 FixLibrary（与 FixEngine 通信的接口）
 * 2. 注册 SessionHandler（处理每个会话的消息）
 * 3. 轮询处理消息（doWork 方法会被定期调用）
 */
public class BuySellGatewayAgent implements Agent {
    private static final int FRAGMENT_LIMIT = 10;

    private FixLibrary library;

    @Override
    public void onStart() {
        // 配置 LibraryConfiguration
        final LibraryConfiguration configuration = new LibraryConfiguration();

        // 注册会话获取处理器：当新会话建立时，会调用这个处理器
        configuration
                .sessionAcquireHandler((session, acquiredInfo) -> onAcquire(session))
                .sessionExistsHandler(new AcquiringSessionExistsHandler(true))
                .libraryAeronChannels(singletonList(IPC_CHANNEL));

        // 连接到 FixLibrary
        library = FixLibrary.connect(configuration);

        System.out.println("✅ FixLibrary 连接成功");
    }

    /**
     * 当新会话建立时调用
     * 返回一个 SessionHandler 来处理该会话的所有消息
     */
    private SessionHandler onAcquire(final Session session) {
        final String senderCompID = session.compositeKey().remoteCompId();
        System.out.println("📥 新会话建立: " + session.compositeKey());
        // 从 Gateway 的角度：remoteCompId 是客户端的 SenderCompID，localCompId 是 Gateway 的
        // TargetCompID
        System.out.println("   客户端 SenderCompID: " + senderCompID);
        System.out.println("   Gateway TargetCompID: " + session.compositeKey().localCompId());
        System.out.println("   会话类型: " + (senderCompID.equals("BUY") ? "📈 BUY 客户端" : senderCompID.equals("SELL") ? "📉 SELL 客户端" : "未知"));
        System.out.flush();
        return new BuySellSessionHandler(session);
    }

    /**
     * Agent 的核心方法，会被定期调用
     * 返回处理的消息片段数量
     */
    @Override
    public int doWork() {
        // poll 方法会处理所有待处理的消息
        // FRAGMENT_LIMIT 限制每次处理的最大片段数
        final int workDone = library.poll(FRAGMENT_LIMIT);
        // 调试：如果处理了消息，输出日志（避免日志过多，只在有消息时输出）
        if (workDone > 0) {
            System.out.println("[DEBUG] Agent 处理了 " + workDone + " 个消息片段");
            System.out.flush();
        }
        return workDone;
    }

    @Override
    public String roleName() {
        return "BuySellGateway";
    }
}
