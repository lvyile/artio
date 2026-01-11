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

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.driver.MediaDriver;
import org.agrona.IoUtil;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.SampleUtil;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.validation.AuthenticationStrategy;
import uk.co.real_logic.artio.validation.MessageValidationStrategy;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static io.aeron.archive.Archive.Configuration.REPLICATION_CHANNEL_PROP_NAME;
import static io.aeron.archive.client.AeronArchive.Configuration.CONTROL_CHANNEL_PROP_NAME;
import static io.aeron.archive.client.AeronArchive.Configuration.CONTROL_RESPONSE_CHANNEL_PROP_NAME;
import static io.aeron.driver.ThreadingMode.SHARED;
import static uk.co.real_logic.artio.CommonConfiguration.backoffIdleStrategy;

/**
 * BuySell Gateway Application - 支持 BUY 和 SELL 两个客户端的 FIX Gateway
 * 
 * 配置说明：
 * - 监听地址: localhost:9999
 * - TargetCompID: FGW
 * - 允许的 SenderCompID: BUY, SELL
 * - FIX 版本: FIX.4.2
 */
public final class BuySellGatewayApplication {
    // Gateway 的 CompID（从客户端角度看是 TargetCompID）
    public static final String TARGET_COMP_ID = "FGW";

    // 允许连接的客户端 CompID（从客户端角度看是 SenderCompID）
    public static final List<String> ALLOWED_SENDER_COMP_IDS = Arrays.asList("BUY", "SELL");

    public static void main(final String[] args) throws Exception {
        System.out.println("=== BuySell Gateway 启动 ===");
        System.out.println("TargetCompID: " + TARGET_COMP_ID);
        System.out.println("允许的 SenderCompID: " + ALLOWED_SENDER_COMP_IDS);
        System.out.println("监听端口: 9999");
        System.out.println();

        // 配置 Aeron 通信通道（Artio 内部使用 Aeron 进行高性能消息传递）
        System.setProperty(CONTROL_CHANNEL_PROP_NAME, "aeron:udp?endpoint=localhost:10010");
        System.setProperty(CONTROL_RESPONSE_CHANNEL_PROP_NAME, "aeron:udp?endpoint=localhost:10020");
        System.setProperty(REPLICATION_CHANNEL_PROP_NAME, "aeron:udp?endpoint=localhost:0");

        // 配置认证策略：只允许 BUY 和 SELL 客户端连接
        // MessageValidationStrategy 用于验证消息的合法性
        final MessageValidationStrategy validationStrategy = MessageValidationStrategy.targetCompId(TARGET_COMP_ID)
                .and(MessageValidationStrategy.senderCompId(ALLOWED_SENDER_COMP_IDS));

        // 将验证策略转换为认证策略
        final AuthenticationStrategy authenticationStrategy = AuthenticationStrategy.of(validationStrategy);

        // 配置 FIX Engine（这是 Artio 的核心组件，负责处理 FIX 协议）
        final EngineConfiguration configuration = new EngineConfiguration()
                .bindTo("localhost", 9999) // 监听 localhost:9999
                .libraryAeronChannel(IPC_CHANNEL) // 使用 IPC 通道（进程内通信，性能最好）
                .logFileDir("buy-sell-gateway"); // 日志文件目录

        // 设置认证策略
        configuration.authenticationStrategy(authenticationStrategy);

        // 清理旧的日志目录（避免序列号冲突）
        cleanupOldLogFileDir(configuration);

        // 配置 Aeron MediaDriver（Aeron 是底层的高性能消息传递库）
        final MediaDriver.Context context = new MediaDriver.Context()
                .threadingMode(SHARED) // 共享线程模式
                .sharedIdleStrategy(backoffIdleStrategy())
                .dirDeleteOnStart(true); // 启动时删除旧目录

        // 配置 Aeron Archive（用于消息持久化）
        final Archive.Context archiveContext = new Archive.Context()
                .threadingMode(ArchiveThreadingMode.SHARED)
                .idleStrategySupplier(CommonConfiguration::backoffIdleStrategy)
                .deleteArchiveOnStart(true); // 启动时删除旧归档

        // 启动 MediaDriver 和 Archive，然后启动 FixEngine
        try (ArchivingMediaDriver driver = ArchivingMediaDriver.launch(context, archiveContext);
                FixEngine gateway = FixEngine.launch(configuration)) {
            System.out.println("✅ Gateway 启动成功！");
            System.out.println("等待客户端连接...");
            System.out.println("按 Ctrl+C 停止 Gateway");
            System.out.println();

            // 运行 Agent 处理消息（Agent 会连接 FixLibrary 并处理会话）
            SampleUtil.runAgentUntilSignal(new BuySellGatewayAgent(), driver.mediaDriver());
        }

        System.out.println("Gateway 已关闭");
        System.exit(0);
    }

    /**
     * 清理旧的日志文件目录
     * 这很重要，因为 FIX 协议使用序列号，如果使用旧的日志目录，
     * 可能会导致序列号不匹配的问题
     */
    public static void cleanupOldLogFileDir(final EngineConfiguration configuration) {
        final File logDir = new File(configuration.logFileDir());
        if (logDir.exists()) {
            System.out.println("清理旧的日志目录: " + logDir.getAbsolutePath());
            IoUtil.delete(logDir, true);
        }
    }
}
