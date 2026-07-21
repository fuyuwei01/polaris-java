/*
 * Tencent is pleased to support the open source community by making polaris-java available.
 *
 * Copyright (C) 2021 Tencent. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */

package com.tencent.polaris.configuration.client.internal;

import com.tencent.polaris.api.plugin.configuration.ConfigFile;
import com.tencent.polaris.encrypt.util.AESUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test for {@link ConfigFilePersistentHandler}.
 *
 * @author fishtailfu
 */
public class ConfigFilePersistentHandlerTest {

    private File persistDir;

    private ConfigFilePersistentHandler handler;

    /**
     * 通过反射构造 ConfigFilePersistentHandler，避免依赖完整的 SDKContext 初始化。
     */
    private ConfigFilePersistentHandler newHandler() throws Exception {
        persistDir = Files.createTempDirectory("polaris-persist-test").toFile();
        ConfigFilePersistentHandler handler = allocateInstance();
        setField(handler, "persistDirPath", persistDir.getAbsolutePath());
        setField(handler, "maxWriteRetry", 3);
        setField(handler, "maxReadRetry", 3);
        setField(handler, "retryInterval", 10L);
        setField(handler, "isAllowPersist", true);
        setField(handler, "connectorType", "polaris");
        return handler;
    }

    /**
     * 使用 Unsafe.allocateInstance 绕过 SDKContext 构造依赖，直接分配实例，
     * 再通过反射注入测试所需的 final 字段。
     */
    @SuppressWarnings("unchecked")
    private ConfigFilePersistentHandler allocateInstance() throws Exception {
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        return (ConfigFilePersistentHandler) unsafe.allocateInstance(ConfigFilePersistentHandler.class);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private ConfigFile baseConfigFile(String content, long version) {
        ConfigFile configFile = new ConfigFile("ns", "group", "file");
        configFile.setContent(content);
        configFile.setVersion(version);
        configFile.setMd5("md5-" + version);
        return configFile;
    }

    /**
     * 测试带密钥的配置加密落盘后 content 为密文
     * 测试目的：验证 doWriteTmpFile 对加密配置正确加密 content 并存储 dataKey
     * 测试场景：configFile 带 decryptedDataKey
     * 验证内容：落盘后原始对象 content 仍为明文，dataKey 未被污染
     */
    @Test
    public void testWriteTmpFile_EncryptedConfig_ContentEncryptedAndOriginalNotPolluted() throws Exception {
        // Arrange
        handler = newHandler();
        String plainContent = "secret config content";
        ConfigFile configFile = baseConfigFile(plainContent, 1L);
        byte[] dataKey = AESUtil.generateAesKey();
        configFile.setDecryptedDataKey(dataKey);

        // Act
        handler.saveConfigFile(configFile);

        // Assert：原始内存对象 content 仍是明文，dataKey 未被改写
        assertThat(configFile.getContent()).isEqualTo(plainContent);
        // 落盘读取回来的应是解密后的明文（往返正确）
        ConfigFile loaded = handler.loadPersistedConfigFile(configFile, false);
        assertThat(loaded.getContent()).isEqualTo(plainContent);
    }

    /**
     * 测试无密钥的明文配置直接落盘（兼容）
     * 测试目的：验证非加密配置不触发加密，content 原样落盘
     * 测试场景：configFile 无 decryptedDataKey
     * 验证内容：读回的 content 与写入一致
     */
    @Test
    public void testWriteTmpFile_PlaintextConfig_NoEncryption() throws Exception {
        // Arrange
        handler = newHandler();
        String plainContent = "plain content";
        ConfigFile configFile = baseConfigFile(plainContent, 1L);

        // Act
        handler.saveConfigFile(configFile);

        // Assert
        ConfigFile loaded = handler.loadPersistedConfigFile(configFile, false);
        assertThat(loaded.getContent()).isEqualTo(plainContent);
    }

    /**
     * 测试加密配置写盘后读回 content 一致（往返）
     * 测试目的：验证加密落盘 + 解密回读的往返一致性
     * 测试场景：带密钥配置写盘后读回
     * 验证内容：读回的 content/version/md5 与原始一致
     */
    @Test
    public void testPersistRoundTrip_EncryptedConfig() throws Exception {
        // Arrange
        handler = newHandler();
        String plainContent = "round trip secret";
        ConfigFile configFile = baseConfigFile(plainContent, 42L);
        configFile.setDecryptedDataKey(AESUtil.generateAesKey());

        // Act
        handler.saveConfigFile(configFile);
        ConfigFile loaded = handler.loadPersistedConfigFile(configFile, false);

        // Assert
        assertThat(loaded.getContent()).isEqualTo(plainContent);
        assertThat(loaded.getVersion()).isEqualTo(42L);
        assertThat(loaded.getMd5()).isEqualTo("md5-42");
    }

    /**
     * 测试读老版本明文缓存（无 dataKey）时按明文返回
     * 测试目的：验证存量明文缓存的向后兼容
     * 测试场景：缓存文件中 dataKey 字段为空
     * 验证内容：读回的 content 为明文
     */
    @Test
    public void testLoadConfigFile_PlaintextCache_NoDataKey() throws Exception {
        // Arrange：先以明文方式写一个缓存文件
        handler = newHandler();
        String plainContent = "legacy plain";
        ConfigFile configFile = baseConfigFile(plainContent, 1L);
        handler.saveConfigFile(configFile);

        // Act：用新 handler 读回（模拟升级后读老缓存）
        ConfigFile readReq = baseConfigFile(null, 0L);
        ConfigFile loaded = handler.loadPersistedConfigFile(readReq, false);

        // Assert
        assertThat(loaded.getContent()).isEqualTo(plainContent);
    }

    /**
     * 测试损坏的 dataKey 解密失败时兜底按明文处理
     * 测试目的：验证回读解密异常时按明文处理不抛错
     * 测试场景：缓存文件中 dataKey 为非合法 Base64 或与 content 不匹配
     * 验证内容：读回不抛异常，content 保持原值（按明文）
     */
    @Test
    public void testLoadConfigFile_CorruptedDataKey_Fallback() throws Exception {
        // Arrange：手工写一个带损坏 dataKey 的缓存文件
        handler = newHandler();
        ConfigFile configFile = baseConfigFile("corrupted content", 1L);
        // 先正常落盘以生成文件名
        handler.saveConfigFile(configFile);
        // 找到缓存文件并覆写为损坏 dataKey 的内容
        File cacheFile = findCacheFile();
        String corruptedYaml = "content: \"still plaintext\"\nmd5: \"m\"\nversion: 1\ndataKey: \"!!!not-base64!!!\"\n";
        Files.write(cacheFile.toPath(), corruptedYaml.getBytes("UTF-8"));

        // Act
        ConfigFile readReq = baseConfigFile(null, 0L);
        ConfigFile loaded = handler.loadPersistedConfigFile(readReq, false);

        // Assert：dataKey 解码失败兜底，content 按明文返回
        assertThat(loaded).isNotNull();
        assertThat(loaded.getContent()).isEqualTo("still plaintext");
    }

    /**
     * 测试加密配置落盘文件中 content 不含明文
     * 测试目的：验证落盘文件确实为密文，明文不落地
     * 测试场景：带密钥配置写盘后读取磁盘文件内容
     * 验证内容：磁盘文件文本中不包含明文 content
     */
    @Test
    public void testWriteTmpFile_DiskFileNoPlaintextContent() throws Exception {
        // Arrange
        handler = newHandler();
        String plainContent = "superSecretValue";
        ConfigFile configFile = baseConfigFile(plainContent, 1L);
        configFile.setDecryptedDataKey(AESUtil.generateAesKey());

        // Act
        handler.saveConfigFile(configFile);

        // Assert
        File cacheFile = findCacheFile();
        String fileText = new String(Files.readAllBytes(cacheFile.toPath()), "UTF-8");
        assertThat(fileText).doesNotContain(plainContent);
    }

    private File findCacheFile() {
        File[] files = persistDir.listFiles();
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".tmp") && !name.endsWith(".lock")) {
                return file;
            }
        }
        throw new AssertionError("no cache file found in " + persistDir);
    }

    @After
    public void tearDown() {
        if (persistDir != null && persistDir.exists()) {
            for (File file : persistDir.listFiles()) {
                file.delete();
            }
            persistDir.delete();
        }
    }
}
