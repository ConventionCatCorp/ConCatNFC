#include <string.h>
#include "PN532InterfaceI2C.h"
#include "esp_log.h"

static const char TAG[] = "pn532_driver_i2c";

#define PN532_I2C_RAW_ADDRESS               (0x24)

typedef struct {
    gpio_num_t sda;
    gpio_num_t scl;
    i2c_port_num_t i2c_port_number;
    i2c_master_bus_handle_t i2c_bus_handle;
    i2c_master_dev_handle_t i2c_dev_handle;
    bool bus_created;
    uint8_t frame_buffer[256];
} pn532_i2c_driver_config;

PN532InterfaceI2C::PN532InterfaceI2C(gpio_num_t sda,
                               gpio_num_t scl,
                               gpio_num_t reset,
                               gpio_num_t irq,
                               i2c_port_num_t i2c_port_number)
{
    m_reset = reset;
    m_irq = irq;

    m_i2c_port_number = i2c_port_number;
    m_scl = scl;
    m_sda = sda;
    m_bus_created = false;

#ifdef CONFIG_ENABLE_IRQ_ISR
    io_handle->m_IRQQueue = NULL;
#endif

    return;
}

esp_err_t PN532InterfaceI2C::pn532_init_io()
{
    if (m_bus_created) {
        pn532_release_io();
    }

    m_bus_created = false;
    if (m_scl != GPIO_NUM_NC && m_sda != GPIO_NUM_NC) {
        // create new master bus
        i2c_master_bus_config_t conf = {
                //Open the I2C Bus
                .i2c_port = m_i2c_port_number,
                .sda_io_num = m_sda,
                .scl_io_num = m_scl,
                .clk_source = I2C_CLK_SRC_DEFAULT,
                .glitch_ignore_cnt = 7,
                .flags = {
                        .enable_internal_pullup = true,
                        .allow_pd = true,
                        },

        };
        if (i2c_new_master_bus(&conf, &m_i2c_bus_handle) != ESP_OK) {
            ESP_LOGE(TAG, "i2c_new_master_bus() failed");
            return ESP_FAIL;
        }
        m_bus_created = true;
    }
    else {
        // try to get bus handle
        if (i2c_master_get_bus_handle(m_i2c_port_number, &m_i2c_bus_handle) != ESP_OK) {
            ESP_LOGE(TAG, "i2c_master_get_bus_handle() failed");
            return ESP_FAIL;
        }
    }

    i2c_device_config_t dev_cfg;
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = PN532_I2C_RAW_ADDRESS; // 7-bit address without RW flag
    dev_cfg.scl_speed_hz = 100000;
    dev_cfg.scl_wait_us = 200000;

    if (i2c_master_bus_add_device(m_i2c_bus_handle, &dev_cfg, &m_i2c_dev_handle) != ESP_OK) {
        ESP_LOGE(TAG, "i2c_master_bus_add_device() failed");
        return ESP_FAIL;
    }

    return ESP_OK;
}

void PN532InterfaceI2C::pn532_release_io()
{
    if (m_i2c_dev_handle != NULL) {
        ESP_LOGD(TAG, "remove i2c device ...");
        i2c_master_bus_rm_device(m_i2c_dev_handle);
        m_i2c_dev_handle = NULL;
    }

    if (m_i2c_bus_handle != NULL) {
        if (m_bus_created) {
            ESP_LOGD(TAG, "delete i2c bus ...");
            i2c_del_master_bus(m_i2c_bus_handle);
            m_bus_created = false;
        }
        m_i2c_bus_handle = NULL;
    }
}

esp_err_t PN532InterfaceI2C::pn532_is_ready()
{
    uint8_t status;
    esp_err_t result = i2c_master_receive(m_i2c_dev_handle, &status, 1, 10);

    if (result != ESP_OK)
        return result;

    return (status == 0x01) ? ESP_OK : ESP_FAIL;
}

esp_err_t PN532InterfaceI2C::pn532_read(uint8_t *read_buffer, size_t read_size, int xfer_timeout_ms, uart_port_t uart_port_to_monitor)
{
    static uint8_t rx_buffer[256];


    TickType_t start_ticks = xTaskGetTickCount();
    TickType_t timeout_ticks = (xfer_timeout_ms > 0) ? pdMS_TO_TICKS(xfer_timeout_ms) : portMAX_DELAY;
    TickType_t elapsed_ticks = 0;

    int read_timeout = (xfer_timeout_ms > 0) ? xfer_timeout_ms : 100;

    esp_err_t result = ESP_FAIL;
    bool is_ready = false;
    while (!is_ready && elapsed_ticks < timeout_ticks) {
        result = i2c_master_receive(m_i2c_dev_handle, rx_buffer, read_size + 1, read_timeout);
        if (result == ESP_OK && rx_buffer[0] == 0x01) {
            is_ready = true;
        }
        elapsed_ticks = xTaskGetTickCount() - start_ticks;
    }

    if (result != ESP_OK)
        return result;

    // check status byte if PN532 is ready
    if (rx_buffer[0] != 0x01) {
        // PN532 not ready
        return ESP_ERR_TIMEOUT;
    }

    // skip status byte and copy only response data
    memcpy(read_buffer, rx_buffer + 1, read_size);
    return result;
}

esp_err_t PN532InterfaceI2C::pn532_write(const uint8_t *write_buffer, size_t write_size, int xfer_timeout_ms)
{
    if (write_size > 254) {
        return ESP_ERR_INVALID_SIZE;
    }

    m_frame_buffer[0] = 0;
    memcpy(m_frame_buffer + 1, write_buffer, write_size);
    m_frame_buffer[write_size + 1] = 0;

    return i2c_master_transmit(m_i2c_dev_handle, m_frame_buffer, write_size + 2, xfer_timeout_ms);
}
