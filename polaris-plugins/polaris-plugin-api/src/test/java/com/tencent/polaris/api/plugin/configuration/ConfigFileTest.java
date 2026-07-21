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

package com.tencent.polaris.api.plugin.configuration;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.assertj.core.api.Assertions;
import org.junit.Test;

/**
 * Test for {@link ConfigFile}.
 *
 * @author fishtailfu
 */
public class ConfigFileTest {

    /**
     * 测试 decryptedDataKey 的 getter 和 setter
     * 测试目的：验证 transient 字段 decryptedDataKey 可以正确设置和读取
     * 测试场景：设置密钥后读回
     * 验证内容：getDecryptedDataKey 返回 setDecryptedDataKey 设置的值
     */
    @Test
    public void testDecryptedDataKey_GetSet() {
        // Arrange
        ConfigFile configFile = new ConfigFile("ns", "group", "file");
        byte[] dataKey = new byte[]{1, 2, 3, 4};

        // Act
        configFile.setDecryptedDataKey(dataKey);

        // Assert
        Assertions.assertThat(configFile.getDecryptedDataKey()).isEqualTo(dataKey);
    }

    /**
     * 测试 toString 不含 decryptedDataKey
     * 测试目的：验证密钥不会通过 toString 泄露到日志
     * 测试场景：设置密钥后调用 toString
     * 验证内容：toString 输出中不包含 decryptedDataKey 相关内容
     */
    @Test
    public void testDecryptedDataKey_NotInToString() {
        // Arrange
        ConfigFile configFile = new ConfigFile("ns", "group", "file");
        byte[] dataKey = new byte[]{1, 2, 3, 4};
        configFile.setDecryptedDataKey(dataKey);

        // Act
        String str = configFile.toString();

        // Assert
        Assertions.assertThat(str).doesNotContain("decryptedDataKey");
        Assertions.assertThat(str).doesNotContain("[1, 2, 3, 4]");
    }

    /**
     * 测试 decryptedDataKey 不参与 equals/hashCode
     * 测试目的：验证密钥不同但其他字段相同时，equals 仍为 true
     * 测试场景：两个对象除 decryptedDataKey 外字段相同
     * 验证内容：equals 为 true，hashCode 一致
     */
    @Test
    public void testDecryptedDataKey_NotInEqualsHashCode() {
        // Arrange
        ConfigFile configFile1 = new ConfigFile("ns", "group", "file");
        configFile1.setContent("content");
        configFile1.setVersion(1L);
        configFile1.setMd5("md5");
        configFile1.setDecryptedDataKey(new byte[]{1, 2, 3, 4});

        ConfigFile configFile2 = new ConfigFile("ns", "group", "file");
        configFile2.setContent("content");
        configFile2.setVersion(1L);
        configFile2.setMd5("md5");
        configFile2.setDecryptedDataKey(new byte[]{9, 9, 9, 9});

        // Assert
        Assertions.assertThat(configFile1).isEqualTo(configFile2);
        Assertions.assertThat(configFile1.hashCode()).isEqualTo(configFile2.hashCode());
    }

    /**
     * 测试 decryptedDataKey 不被 YAMLMapper 序列化
     * 测试目的：验证 transient + @JsonIgnore 生效，密钥不会随 YAML 落盘
     * 测试场景：设置密钥后用 YAMLMapper 序列化
     * 验证内容：序列化结果不含 decryptedDataKey
     */
    @Test
    public void testDecryptedDataKey_NotSerializedByYamlMapper() throws Exception {
        // Arrange
        ConfigFile configFile = new ConfigFile("ns", "group", "file");
        configFile.setContent("content");
        configFile.setDecryptedDataKey(new byte[]{1, 2, 3, 4});

        // Act
        String yaml = new YAMLMapper().writeValueAsString(configFile);

        // Assert
        Assertions.assertThat(yaml).doesNotContain("decryptedDataKey");
    }
}
