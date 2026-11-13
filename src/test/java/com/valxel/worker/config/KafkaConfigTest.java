package com.valxel.worker.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KafkaConfigTest {

    @Test
    void phiAndPaymentsNeverReadKafkaInPlainText() {
        assertThatThrownBy(() -> KafkaConfig.requireEncryption("phi", "PLAINTEXT")).hasMessageContaining("must use TLS");
        assertThatThrownBy(() -> KafkaConfig.requireEncryption("payments", "sasl_plaintext"))
                .hasMessageContaining("SASL_PLAINTEXT");
        KafkaConfig.requireEncryption("phi", "SASL_SSL");
        KafkaConfig.requireEncryption("general", "PLAINTEXT");
    }
}
