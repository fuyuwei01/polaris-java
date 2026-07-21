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
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.polaris.plugins.configfilefilter;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.function.Function;

import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.exception.ServerCodes;
import com.tencent.polaris.api.plugin.PluginType;
import com.tencent.polaris.api.plugin.common.InitContext;
import com.tencent.polaris.api.plugin.compose.Extensions;
import com.tencent.polaris.api.plugin.configuration.ConfigFile;
import com.tencent.polaris.api.plugin.configuration.ConfigFileResponse;
import com.tencent.polaris.api.plugin.filter.Crypto;
import com.tencent.polaris.factory.config.configuration.CryptoConfigImpl;
import com.tencent.polaris.plugins.configfilefilter.service.RSAService;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author fabian4
 * @date 2023/6/14
 */
public class CryptoConfigFileFilterTest {

    private Crypto crypto;

    private RSAService rsaService;

    /**
     * 可控返回值的 RSAService 桩，避免依赖 Mockito 静态代理。
     */
    private static final class StubRSAService extends RSAService {

        private final byte[] decrypted;

        StubRSAService(byte[] decrypted) {
            this.decrypted = decrypted;
        }

        @Override
        public String getPKCS1PublicKey() {
            return "RSAPublicKey";
        }

        @Override
        public byte[] decrypt(String context) {
            return decrypted;
        }
    }

    @Before
    public void setUp() {
        rsaService = new StubRSAService(null);
        crypto = new Crypto() {
            @Override
            public void doEncrypt(ConfigFile configFile) {

            }

            @Override
            public void doDecrypt(ConfigFile configFile, byte[] password) {
                configFile.setContent(configFile.getContent() + "-doCrypto");
            }

            @Override
            public String getName() {
                return null;
            }

            @Override
            public PluginType getType() {
                return null;
            }

            @Override
            public void init(InitContext ctx) throws PolarisException {

            }

            @Override
            public void postContextInit(Extensions ctx) throws PolarisException {

            }

            @Override
            public void destroy() {

            }
        };
    }

    @Test
    public void testDoFilter() {
        String content = "content";
        ConfigFile configFile = new ConfigFile("namespace", "group", "fileName");
        configFile.setContent(content);

        CryptoConfigFileFilter cryptoConfigFileFilter =
                new CryptoConfigFileFilter(crypto, rsaService, new CryptoConfigImpl(), new HashMap<>());

        ConfigFileResponse response = cryptoConfigFileFilter.doFilter(configFile,
                new Function<ConfigFile, ConfigFileResponse>() {
            @Override
            public ConfigFileResponse apply(ConfigFile configFile) {
                configFile.setContent(configFile.getContent() + "-apply");
                configFile.setDataKey(configFile.getPublicKey());
                return new ConfigFileResponse(ServerCodes.EXECUTE_SUCCESS, "OK", configFile);
            }
        }).apply(configFile);

        String res = content + "-apply" + "-doCrypto";
        assertThat(response.getConfigFile().getContent()).isEqualTo(res);
        assertThat(configFile.getDataKey()).isEqualTo("RSAPublicKey");
    }

    /**
     * 测试传输加密配置解密成功后回填 decryptedDataKey
     * 测试目的：验证 doFilter 在解密成功路径回填 AES 密钥，供缓存加密使用
     * 测试场景：dataKey 非空、响应码成功
     * 验证内容：response.getConfigFile().getDecryptedDataKey() 与 rsaService.decrypt 返回值一致
     */
    @Test
    public void testDoFilter_EncryptedConfig_SetDecryptedDataKey() {
        // Arrange
        byte[] password = "1234567890123456".getBytes(StandardCharsets.UTF_8);
        rsaService = new StubRSAService(password);
        String content = "secretContent";
        ConfigFile configFile = new ConfigFile("namespace", "group", "fileName");
        configFile.setContent(content);

        CryptoConfigFileFilter cryptoConfigFileFilter =
                new CryptoConfigFileFilter(crypto, rsaService, new CryptoConfigImpl(), new HashMap<>());

        // Act
        ConfigFileResponse response = cryptoConfigFileFilter.doFilter(configFile,
                new Function<ConfigFile, ConfigFileResponse>() {
                    @Override
                    public ConfigFileResponse apply(ConfigFile configFile) {
                        configFile.setContent(configFile.getContent() + "-apply");
                        configFile.setDataKey("encryptedDataKey");
                        return new ConfigFileResponse(ServerCodes.EXECUTE_SUCCESS, "OK", configFile);
                    }
                }).apply(configFile);

        // Assert
        assertThat(response.getCode()).isEqualTo(ServerCodes.EXECUTE_SUCCESS);
        assertThat(response.getConfigFile().getDecryptedDataKey()).isEqualTo(password);
    }

    /**
     * 测试明文配置（无 dataKey）不回填 decryptedDataKey
     * 测试目的：验证明文拉取的配置不会误触发密钥回填
     * 测试场景：响应成功但 dataKey 为 null
     * 验证内容：response.getConfigFile().getDecryptedDataKey() 为 null
     */
    @Test
    public void testDoFilter_PlaintextConfig_NoDataKey() {
        // Arrange
        byte[] password = "1234567890123456".getBytes(StandardCharsets.UTF_8);
        rsaService = new StubRSAService(password);
        String content = "plainContent";
        ConfigFile configFile = new ConfigFile("namespace", "group", "fileName");
        configFile.setContent(content);

        CryptoConfigFileFilter cryptoConfigFileFilter =
                new CryptoConfigFileFilter(crypto, rsaService, new CryptoConfigImpl(), new HashMap<>());

        // Act：服务端返回时 dataKey 为 null（明文配置）
        ConfigFileResponse response = cryptoConfigFileFilter.doFilter(configFile,
                new Function<ConfigFile, ConfigFileResponse>() {
                    @Override
                    public ConfigFileResponse apply(ConfigFile configFile) {
                        configFile.setContent(configFile.getContent() + "-apply");
                        return new ConfigFileResponse(ServerCodes.EXECUTE_SUCCESS, "OK", configFile);
                    }
                }).apply(configFile);

        // Assert
        assertThat(response.getCode()).isEqualTo(ServerCodes.EXECUTE_SUCCESS);
        assertThat(response.getConfigFile().getDecryptedDataKey()).isNull();
    }
}
