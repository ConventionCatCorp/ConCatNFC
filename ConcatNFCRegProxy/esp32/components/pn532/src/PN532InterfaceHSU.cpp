#include <string.h>
#include "PN532Interface.h"
#include "PN532InterfaceHSU.h"
#include "esp_log.h"

static const char TAG[] = "pn532_driver_hsu";

const int32_t baud_config_table[] = {
        9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600, 1288000
};

static const uint8_t get_firmware_frame[] = { 0x00, 0xFF, 0x02, 0xFE, 0xD4, 0x02, 0x2A };
static const uint8_t set_serial_baud_rate_resp_frame[] = { 0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD5, 0x11, 0x1A, 0x00 };

PN532InterfaceHSU::PN532InterfaceHSU(gpio_num_t uart_rx, gpio_num_t uart_tx, gpio_num_t reset, gpio_num_t irq, uart_port_t uart_port, int32_t baudrate)
{
    ESP_LOGD(TAG, "pn532_new_driver_hsu(): uart_rx=%d uart_tx=%d reset=%d irq=%d, uart_port=%d, baudrate=%ld", uart_rx, uart_tx, reset, irq, uart_port, baudrate);
    m_reset = reset;
    m_irq = irq;
    m_hasIsReady = false;

    m_uart_port = uart_port;
    m_uart_rx = uart_rx;
    m_uart_tx = uart_tx;
    m_uart_baud_used = 0;

    m_uart_baud_wanted = 0x04; // 115200 baud
    for (int n=0; n < (sizeof(baud_config_table)/ sizeof(baud_config_table[0])); ++n) {
        if (baud_config_table[n] == baudrate) {
            m_uart_baud_wanted = n;
            break;
        }
    }
    if (baud_config_table[m_uart_baud_wanted] != baudrate) {
        ESP_LOGW(TAG, "pn532_new_driver_hsu(): Unsupported baud rate %ld -> using standard baud rate 115200", baudrate);
    }
    
#ifdef CONFIG_ENABLE_IRQ_ISR
    m_IRQQueue = NULL;
#endif
}

esp_err_t PN532InterfaceHSU::pn532_init_io()
{
    m_uart_baud_used = 0x04;
    uart_config_t uart_config = {
            .baud_rate = baud_config_table[m_uart_baud_used],
            .data_bits = UART_DATA_8_BITS,
            .parity    = UART_PARITY_DISABLE,
            .stop_bits = UART_STOP_BITS_1,
            .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
            .rx_flow_ctrl_thresh = 0,
            .source_clk = UART_SCLK_DEFAULT,
            .flags = {
                 .allow_pd = 0,
                 .backup_before_sleep = 0,
            }
    };

    if(ESP_OK != uart_driver_install(m_uart_port, 256, 256, 0, NULL, 0)) {
        ESP_LOGE(TAG, "uart_driver_install() failed");
        return ESP_FAIL;
    }

    if (ESP_OK != uart_param_config(m_uart_port, &uart_config)) {
        ESP_LOGE(TAG, "uart_param_config() failed");
        return ESP_FAIL;
    }
    if (ESP_OK != uart_set_pin(m_uart_port, m_uart_tx,
                               m_uart_rx, GPIO_NUM_NC, GPIO_NUM_NC)) {
        ESP_LOGE(TAG, "uart_set_pin() failed");
        return ESP_FAIL;
    }

    ESP_LOGD(TAG, "pn532_init_io(): check baud rate %ld ...", baud_config_table[m_uart_baud_used]);
    ESP_LOGD(TAG, "m_isSAMConfigDone = %s", m_isSAMConfigDone ? "true" : "false");
    esp_err_t err = pn532_write(get_firmware_frame, sizeof(get_firmware_frame), 100);
    if (err != ESP_OK) {
        ESP_LOGD(TAG, "pn532_init_io(): failed to send get firmware frame");
    }

    uint8_t buf[8];
    err = pn532_read(buf, 6, 100);
    if (err == ESP_OK && buf[3] == 0x00 && buf[4] == 0xFF) {
        return ESP_OK;
    }

    if (m_uart_baud_used == m_uart_baud_wanted)
        return ESP_FAIL;

    ESP_LOGD(TAG, "pn532_init_io(): try to use configured baud rate %ld ...", baud_config_table[m_uart_baud_wanted]);
    err = uart_set_baudrate(m_uart_port, baud_config_table[m_uart_baud_wanted]);
    if (err != ESP_OK)
        return err;
    m_uart_baud_used = m_uart_baud_wanted;

    err = pn532_write(get_firmware_frame, sizeof(get_firmware_frame), 100);
    if (err != ESP_OK) {
        ESP_LOGD(TAG, "pn532_init_io(): failed to send get firmware frame on configured baudrate");
    }

    err = pn532_read(buf, 6, 100);
    if (err == ESP_OK && buf[3] == 0x00 && buf[4] == 0xFF) {
        return ESP_OK;
    }

    return ESP_FAIL;
}

void PN532InterfaceHSU::pn532_release_io() {
    uart_driver_delete(m_uart_port);
}


esp_err_t PN532InterfaceHSU::pn532_init_extra() {
    esp_err_t err;
    if (m_uart_baud_used != m_uart_baud_wanted)
    {
        uint8_t buf[16];

        // SetSerialBaudRate Frame
        buf[0] = 0x00;
        buf[1] = 0xFF;
        buf[2] = 0x03;
        buf[3] = 0xFD;
        buf[4] = 0xD4;
        buf[5] = 0x10;
        buf[6] = m_uart_baud_wanted;
        buf[7] = ~(buf[4] + buf[5] + buf[6]) + 1;

        ESP_LOGD(TAG, "pn532_init_extra() write set serial frame");
        // write SetSerialBaudRate command
        err = pn532_write(buf, 8, 100);
        if (err != ESP_OK)
            return err;

        ESP_LOGD(TAG, "pn532_init_extra() try to read ACK ...");
        // read ACK
        err = pn532_read(buf, ACK_FRAME_SIZE, 100);
        if (err != ESP_OK)
            return err;

        ESP_LOGD(TAG, "pn532_init_extra() received ACK or NACK:");
        ESP_LOG_BUFFER_HEXDUMP(TAG, buf, 6, ESP_LOG_DEBUG);
        if (0 != memcmp(buf, ACK_FRAME, ACK_FRAME_SIZE))
            return ESP_FAIL;

        ESP_LOGD(TAG, "pn532_init_extra() try to read response frame ...");
        // read response
        err = pn532_read(buf, sizeof(set_serial_baud_rate_resp_frame), 100);
        if (err != ESP_OK)
            return err;

        ESP_LOGD(TAG, "pn532_init_extra() received response frame:");
        ESP_LOG_BUFFER_HEXDUMP(TAG, buf, sizeof(set_serial_baud_rate_resp_frame), ESP_LOG_DEBUG);
        if (0 != memcmp(buf, set_serial_baud_rate_resp_frame, sizeof(set_serial_baud_rate_resp_frame)))
            return ESP_FAIL;

        ESP_LOGD(TAG, "pn532_init_extra() write ACK frame");
        err = pn532_write(ACK_FRAME + 1, ACK_FRAME_SIZE - 2, 100);
        if (err != ESP_OK)
            return err;

        // wait a little bit before changing UART baud rate
        vTaskDelay(2);

        ESP_LOGD(TAG, "pn532_init_extra() change UART baud rate");
        // change baud rate
        err = uart_set_baudrate(m_uart_port, baud_config_table[m_uart_baud_wanted]);
        if (err != ESP_OK)
            return err;
        m_uart_baud_used = m_uart_baud_wanted;
    }
    return ESP_OK;
}

esp_err_t PN532InterfaceHSU::pn532_wakeup()
{
    const static uint8_t wakeup_frame[] = { 0x55, 0x55, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };

    ESP_LOGD(TAG, "pn532_wakeup() sending wakeup frame");
    int result = uart_write_bytes(m_uart_port, wakeup_frame, sizeof(wakeup_frame));

    if (result != sizeof(wakeup_frame)) {
        return ESP_FAIL;
    }

    return uart_wait_tx_done(m_uart_port, pdMS_TO_TICKS(100));
}

esp_err_t PN532InterfaceHSU::pn532_read(uint8_t *read_buffer, size_t read_size, int xfer_timeout_ms, uart_port_t uart_port_to_monitor)
{
    if (read_buffer == NULL || read_size < 6) {
        return ESP_ERR_INVALID_ARG;
    }

    TickType_t start_ticks = xTaskGetTickCount();
    TickType_t timeout_ticks = (xfer_timeout_ms > 0) ? pdMS_TO_TICKS(xfer_timeout_ms) + 1 : portMAX_DELAY;

    int rx_bytes = 0;
    TickType_t elapsed_ticks = 0;

    while (elapsed_ticks < timeout_ticks) {
        rx_bytes = uart_read_bytes(m_uart_port, read_buffer, 6, pdMS_TO_TICKS(100));
        if (rx_bytes > 0 || rx_bytes < 0) {
            break;
        }
        size_t bytesAvailable;
        if (ESP_OK == uart_get_buffered_data_len(uart_port_to_monitor, &bytesAvailable)) {
            if (bytesAvailable > 0) {
                return ESP_ERR_TIMEOUT;
            }
        }
        elapsed_ticks = xTaskGetTickCount() - start_ticks;
    }
    if (rx_bytes != 6) {
        if (rx_bytes < 0)
            return ESP_FAIL;
        return ESP_ERR_TIMEOUT;
    }

    if (0 == memcmp(read_buffer, ACK_FRAME, ACK_FRAME_SIZE)) {
        return ESP_OK;
    }

    if (0 == memcmp(read_buffer, NACK_FRAME, NACK_FRAME_SIZE)) {
        return ESP_OK;
    }

    uint8_t len = read_buffer[3];
    uint8_t lcs = read_buffer[4];
    if (0 != ((len + lcs) & 0xFF)) {
        return ESP_FAIL;
    }

    elapsed_ticks = xTaskGetTickCount() - start_ticks;
    if (elapsed_ticks >= timeout_ticks)
        return ESP_ERR_TIMEOUT;

    int bytes_to_read = len + 1;
    bool frame_truncated = false;
    if (bytes_to_read > (read_size - 6)) {
        bytes_to_read = (int)read_size - 6;
        frame_truncated = true;
    }

    while (elapsed_ticks < timeout_ticks) {
        rx_bytes = uart_read_bytes(m_uart_port, read_buffer + 6, bytes_to_read, pdMS_TO_TICKS(100));
        if (rx_bytes > 0 || rx_bytes < 0) {
            break;
        }
        size_t bytesAvailable;
        if (ESP_OK == uart_get_buffered_data_len(uart_port_to_monitor, &bytesAvailable)) {
            if (bytesAvailable > 0) {
                return ESP_ERR_TIMEOUT;
            }
        }
        elapsed_ticks = xTaskGetTickCount() - start_ticks;
    }
    if (rx_bytes != bytes_to_read) {
        if (rx_bytes < 0)
            return ESP_FAIL;
        return ESP_ERR_TIMEOUT;
    }

    if (!frame_truncated) {
        uint8_t csum = 0;
        uint8_t *data_ptr = read_buffer + 5;
        for (int n=0; n < len; ++n) {
            csum += *data_ptr++;
        }
        csum += *data_ptr; // add DCS
        if (csum != 0) {
            ESP_LOGD(TAG, "pn532_read(): data checksum mismatch!");
            return ESP_FAIL;
        }
    }

    return ESP_OK;
}

esp_err_t PN532InterfaceHSU::pn532_write(const uint8_t *write_buffer, size_t write_size, int xfer_timeout_ms)
{
    if (!m_isSAMConfigDone)
    {
        esp_err_t err = pn532_wakeup();
        ESP_LOGD(TAG, "pn532_wakeup() result = 0x%x", err);
        if (err != ESP_OK) {
            return err;
        }
    }

    // flush receive buffer before sending next command
    uart_flush_input(m_uart_port);

    const uint8_t null_byte[] = {0};
    int result;
    // Preamble
    result = uart_write_bytes(m_uart_port, null_byte, 1);
    if (result != 1) return ESP_FAIL;
    // Frame
    result = uart_write_bytes(m_uart_port, write_buffer, write_size);
    if (result != write_size) return ESP_FAIL;
    // Postamble
    result = uart_write_bytes(m_uart_port, null_byte, 1);
    if (result != 1) return ESP_FAIL;

    return uart_wait_tx_done(m_uart_port, xfer_timeout_ms);
}
