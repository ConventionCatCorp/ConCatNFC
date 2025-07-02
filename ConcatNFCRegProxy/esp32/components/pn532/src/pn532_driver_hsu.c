#include <string.h>
#include "pn532_driver.h"
#include "pn532_driver_hsu.h"
#include "esp_log.h"

static const char TAG[] = "pn532_driver_hsu";

typedef struct {
    gpio_num_t uart_rx;
    gpio_num_t uart_tx;
    uart_port_t uart_port;
    uint8_t uart_baud_wanted;
    uint8_t uart_baud_used;
} pn532_hsu_driver_config;

const int32_t baud_config_table[] = {
        9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600, 1288000
};

static const uint8_t get_firmware_frame[] = { 0x00, 0xFF, 0x02, 0xFE, 0xD4, 0x02, 0x2A };
static const uint8_t set_serial_baud_rate_resp_frame[] = { 0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD5, 0x11, 0x1A, 0x00 };

static esp_err_t pn532_init_io(pn532_io_handle_t io_handle);
static void pn532_release_io(pn532_io_handle_t io_handle);
static void pn532_release_driver(pn532_io_handle_t io_handle);
static esp_err_t pn532_read(pn532_io_handle_t io_handle, uint8_t *read_buffer, size_t read_size, int xfer_timeout_ms);
static esp_err_t pn532_write(pn532_io_handle_t io_handle, const uint8_t *write_buffer, size_t write_size, int xfer_timeout_ms);
static esp_err_t pn532_wakeup(pn532_hsu_driver_config *driver_config);
static esp_err_t pn532_init_extra(pn532_io_handle_t io_handle);

esp_err_t pn532_new_driver_hsu(gpio_num_t uart_rx,
                               gpio_num_t uart_tx,
                               gpio_num_t reset,
                               gpio_num_t irq,
                               uart_port_t uart_port,
                               int32_t baudrate,
                               pn532_io_handle_t io_handle)
{
    if (io_handle == NULL)
        return ESP_ERR_INVALID_ARG;

    if (uart_rx == GPIO_NUM_NC
        || uart_tx == GPIO_NUM_NC
        || uart_port >= UART_NUM_MAX)
        return ESP_ERR_INVALID_ARG;

    pn532_hsu_driver_config *dev_config = heap_caps_calloc(1, sizeof(pn532_hsu_driver_config), MALLOC_CAP_DEFAULT);
    if (dev_config == NULL) {
        return ESP_ERR_NO_MEM;
    }

    io_handle->reset = reset;
    io_handle->irq = irq;

    dev_config->uart_port = uart_port;
    dev_config->uart_rx = uart_rx;
    dev_config->uart_tx = uart_tx;
    dev_config->uart_baud_wanted = 0x04; // 115200 baud
    for (int n=0; n < (sizeof(baud_config_table)/ sizeof(baud_config_table[0])); ++n) {
        if (baud_config_table[n] == baudrate) {
            dev_config->uart_baud_wanted = n;
            break;
        }
    }
    io_handle->driver_data = dev_config;

    if (baud_config_table[dev_config->uart_baud_wanted] != baudrate) {
        ESP_LOGW(TAG, "pn532_new_driver_hsu(): Unsupported baud rate %ld -> using standard baud rate 115200", baudrate);
    }

    io_handle->pn532_init_io = pn532_init_io;
    io_handle->pn532_release_driver = pn532_release_driver;
    io_handle->pn532_release_io = pn532_release_io;
    io_handle->pn532_read = pn532_read;
    io_handle->pn532_write = pn532_write;
    io_handle->pn532_init_extra = pn532_init_extra;
    io_handle->pn532_is_ready = NULL;

#ifdef CONFIG_ENABLE_IRQ_ISR
    io_handle->IRQQueue = NULL;
#endif

    return ESP_OK;
}

static void pn532_release_driver(pn532_io_handle_t io_handle)
{
    if (io_handle == NULL || io_handle->driver_data == NULL)
        return;

    pn532_hsu_driver_config *driver_config = (pn532_hsu_driver_config *)io_handle->driver_data;
    pn532_release_io(io_handle);
    io_handle->driver_data = NULL;
    free(driver_config);
}

esp_err_t pn532_init_io(pn532_io_handle_t io_handle)
{
    if (io_handle == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    if (io_handle->driver_data == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    pn532_hsu_driver_config *driver_config = (pn532_hsu_driver_config *)io_handle->driver_data;

    driver_config->uart_baud_used = 0x04;
    uart_config_t uart_config = {
            .baud_rate = baud_config_table[driver_config->uart_baud_used],
            .data_bits = UART_DATA_8_BITS,
            .parity    = UART_PARITY_DISABLE,
            .stop_bits = UART_STOP_BITS_1,
            .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
            .source_clk = UART_SCLK_DEFAULT,
    };

    if(ESP_OK != uart_driver_install(driver_config->uart_port, 256, 256, 0, NULL, 0)) {
        ESP_LOGE(TAG, "uart_driver_install() failed");
        return ESP_FAIL;
    }

    if (ESP_OK != uart_param_config(driver_config->uart_port, &uart_config)) {
        ESP_LOGE(TAG, "uart_param_config() failed");
        return ESP_FAIL;
    }
    if (ESP_OK != uart_set_pin(driver_config->uart_port, driver_config->uart_tx,
                               driver_config->uart_rx, GPIO_NUM_NC, GPIO_NUM_NC)) {
        ESP_LOGE(TAG, "uart_set_pin() failed");
        return ESP_FAIL;
    }

    ESP_LOGD(TAG, "pn532_init_io(): check baud rate %ld ...", baud_config_table[driver_config->uart_baud_used]);
    esp_err_t err = pn532_write(io_handle, get_firmware_frame, sizeof(get_firmware_frame), 100);
    if (err != ESP_OK) {
        ESP_LOGD(TAG, "pn532_init_io(): failed to send get firmware frame");
    }

    uint8_t buf[8];
    err = pn532_read(io_handle, buf, 6, 100);
    if (err == ESP_OK && buf[3] == 0x00 && buf[4] == 0xFF) {
        return ESP_OK;
    }

    if (driver_config->uart_baud_used == driver_config->uart_baud_wanted)
        return ESP_FAIL;

    ESP_LOGD(TAG, "pn532_init_io(): try to use configured baud rate %ld ...", baud_config_table[driver_config->uart_baud_wanted]);
    err = uart_set_baudrate(driver_config->uart_port, baud_config_table[driver_config->uart_baud_wanted]);
    if (err != ESP_OK)
        return err;
    driver_config->uart_baud_used = driver_config->uart_baud_wanted;

    err = pn532_write(io_handle, get_firmware_frame, sizeof(get_firmware_frame), 100);
    if (err != ESP_OK) {
        ESP_LOGD(TAG, "pn532_init_io(): failed to send get firmware frame on configured baudrate");
    }

    err = pn532_read(io_handle, buf, 6, 100);
    if (err == ESP_OK && buf[3] == 0x00 && buf[4] == 0xFF) {
        return ESP_OK;
    }

    return ESP_FAIL;
}

static void pn532_release_io(pn532_io_handle_t io_handle)
{
    if (io_handle == NULL || io_handle->driver_data == NULL)
        return;

    pn532_hsu_driver_config *driver_config = (pn532_hsu_driver_config *)io_handle->driver_data;

    if (uart_is_driver_installed(driver_config->uart_port))
        uart_driver_delete(driver_config->uart_port);
}

esp_err_t pn532_init_extra(pn532_io_handle_t io_handle)
{
    if (io_handle == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    if (io_handle->driver_data == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    esp_err_t err = ESP_OK;
    pn532_hsu_driver_config *driver_config = (pn532_hsu_driver_config *)io_handle->driver_data;
    if (driver_config->uart_baud_used != driver_config->uart_baud_wanted)
    {
        uint8_t buf[16];

        // SetSerialBaudRate Frame
        buf[0] = 0x00;
        buf[1] = 0xFF;
        buf[2] = 0x03;
        buf[3] = 0xFD;
        buf[4] = 0xD4;
        buf[5] = 0x10;
        buf[6] = driver_config->uart_baud_wanted;
        buf[7] = ~(buf[4] + buf[5] + buf[6]) + 1;

        ESP_LOGD(TAG, "pn532_init_extra() write set serial frame");
        // write SetSerialBaudRate command
        err = pn532_write(io_handle, buf, 8, 100);
        if (err != ESP_OK)
            return err;

        ESP_LOGD(TAG, "pn532_init_extra() try to read ACK ...");
        // read ACK
        err = pn532_read(io_handle, buf, sizeof(ACK_FRAME), 100);
        if (err != ESP_OK)
            return err;

        ESP_LOGD(TAG, "pn532_init_extra() received ACK or NACK:");
        ESP_LOG_BUFFER_HEXDUMP(TAG, buf, 6, ESP_LOG_DEBUG);
        if (0 != memcmp(buf, ACK_FRAME, sizeof(ACK_FRAME)))
            return ESP_FAIL;

        ESP_LOGD(TAG, "pn532_init_extra() try to read response frame ...");
        // read response
        err = pn532_read(io_handle, buf, sizeof(set_serial_baud_rate_resp_frame), 100);
        if (err != ESP_OK)
            return err;

        ESP_LOGD(TAG, "pn532_init_extra() received response frame:");
        ESP_LOG_BUFFER_HEXDUMP(TAG, buf, sizeof(set_serial_baud_rate_resp_frame), ESP_LOG_DEBUG);
        if (0 != memcmp(buf, set_serial_baud_rate_resp_frame, sizeof(set_serial_baud_rate_resp_frame)))
            return ESP_FAIL;

        ESP_LOGD(TAG, "pn532_init_extra() write ACK frame");
        err = pn532_write(io_handle, ACK_FRAME + 1, sizeof(ACK_FRAME) - 2, 100);
        if (err != ESP_OK)
            return err;

        // wait a little bit before changing UART baud rate
        vTaskDelay(2);

        ESP_LOGD(TAG, "pn532_init_extra() change UART baud rate");
        // change baud rate
        err = uart_set_baudrate(driver_config->uart_port, baud_config_table[driver_config->uart_baud_wanted]);
        if (err != ESP_OK)
            return err;
        driver_config->uart_baud_used = driver_config->uart_baud_wanted;
    }
    return ESP_OK;
}

esp_err_t pn532_wakeup(pn532_hsu_driver_config *driver_config)
{
    const static uint8_t wakeup_frame[] = { 0x55, 0x55, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };

    int result = uart_write_bytes(driver_config->uart_port, wakeup_frame, sizeof(wakeup_frame));

    if (result != sizeof(wakeup_frame)) {
        return ESP_FAIL;
    }

    return uart_wait_tx_done(driver_config->uart_port, pdMS_TO_TICKS(100));
}

esp_err_t pn532_read(pn532_io_handle_t io_handle, uint8_t *read_buffer, size_t read_size, int xfer_timeout_ms)
{
//    static uint8_t frame_buffer[256];

    if (io_handle == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    if (io_handle->driver_data == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    if (read_buffer == NULL || read_size < 6) {
        return ESP_ERR_INVALID_ARG;
    }

    pn532_hsu_driver_config *driver_config = (pn532_hsu_driver_config *)io_handle->driver_data;
    TickType_t start_ticks = xTaskGetTickCount();
    TickType_t timeout_ticks = (xfer_timeout_ms > 0) ? pdMS_TO_TICKS(xfer_timeout_ms) + 1 : portMAX_DELAY;

    int rx_bytes = 0;
    TickType_t elapsed_ticks = 0;

    rx_bytes = uart_read_bytes(driver_config->uart_port, read_buffer, 6, timeout_ticks);
    if (rx_bytes != 6) {
        if (rx_bytes < 0)
            return ESP_FAIL;
        return ESP_ERR_TIMEOUT;
    }

    if (0 == memcmp(read_buffer, ACK_FRAME, sizeof(ACK_FRAME))) {
        return ESP_OK;
    }

    if (0 == memcmp(read_buffer, NACK_FRAME, sizeof(NACK_FRAME))) {
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
    rx_bytes = uart_read_bytes(driver_config->uart_port, read_buffer + 6, bytes_to_read, timeout_ticks - elapsed_ticks);
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

esp_err_t pn532_write(pn532_io_handle_t io_handle, const uint8_t *write_buffer, size_t write_size, int xfer_timeout_ms)
{
    if (io_handle == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    if (io_handle->driver_data == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    pn532_hsu_driver_config *driver_config = (pn532_hsu_driver_config *)io_handle->driver_data;

    if (!io_handle->isSAMConfigDone)
    {
        esp_err_t err = pn532_wakeup(driver_config);
        if (err != ESP_OK) {
            return err;
        }
    }

    // flush receive buffer before sending next command
    uart_flush_input(driver_config->uart_port);

    const uint8_t null_byte[] = {0};
    int result;
    // Preamble
    result = uart_write_bytes(driver_config->uart_port, null_byte, 1);
    if (result != 1) return ESP_FAIL;
    // Frame
    result = uart_write_bytes(driver_config->uart_port, write_buffer, write_size);
    if (result != write_size) return ESP_FAIL;
    // Postamble
    result = uart_write_bytes(driver_config->uart_port, null_byte, 1);
    if (result != 1) return ESP_FAIL;

    return uart_wait_tx_done(driver_config->uart_port, xfer_timeout_ms);
}
