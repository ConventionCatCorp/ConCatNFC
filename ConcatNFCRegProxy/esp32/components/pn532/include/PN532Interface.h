#ifndef CONCAT_NFC_PROXY_ESP32_PN532INTERFACE_H
#define CONCAT_NFC_PROXY_ESP32_PN532INTERFACE_H

#include "esp_err.h"
#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"

#define PN532_PREAMBLE                      (0x00)
#define PN532_STARTCODE1                    (0x00)
#define PN532_STARTCODE2                    (0xFF)
#define PN532_POSTAMBLE                     (0x00)

#define PN532_HOST_TO_PN532                 (0xD4)
#define PN532_PN532TOHOST                   (0xD5)

#define PN532_WRITE_TIMEOUT                 100  // in ms
#define PN532_READ_TIMEOUT                  100  // in ms
#define PN532_READY_WAIT_TIMEOUT            1000 // in ms

// PN532Interface.h
extern const uint8_t ACK_FRAME[];
extern const size_t ACK_FRAME_SIZE;

extern const uint8_t NACK_FRAME[];
extern const size_t NACK_FRAME_SIZE;

class PN532Interface {
public:
    esp_err_t pn532_init();
    void pn532_release();
    void pn532_reset();
    esp_err_t pn532_write_command(const uint8_t *cmd, uint8_t cmdlen, int timeout);
    esp_err_t pn532_read_data(uint8_t *buffer, uint8_t length, int32_t timeout);
    bool pn532_is_ready();
    esp_err_t pn532_poll_ready(int32_t timeout);
    esp_err_t pn532_wait_ready(int32_t timeout);
    esp_err_t pn532_SAM_config();
    esp_err_t pn532_send_command_wait_ack(const uint8_t *cmd, uint8_t cmd_length, int32_t timeout);
    esp_err_t pn532_read_ack();

    virtual esp_err_t pn532_init_io() = 0;
    virtual esp_err_t pn532_read(uint8_t *read_buffer, size_t read_size, int xfer_timeout_ms) = 0;
    virtual esp_err_t pn532_write(const uint8_t *write_buffer, size_t write_size, int xfer_timeout_ms) = 0;
    virtual esp_err_t pn532_init_extra() = 0;
    virtual esp_err_t pn532_wakeup() = 0;

    virtual void pn532_release_io() = 0;

protected:
    gpio_num_t m_reset = GPIO_NUM_NC;
    gpio_num_t m_irq = GPIO_NUM_NC;

    bool m_isSAMConfigDone = false;
    bool m_hasIsReady = true;

#ifdef CONFIG_ENABLE_IRQ_ISR
    QueueHandle_t m_IRQQueue;
#endif
};

#endif //CONCAT_NFC_PROXY_ESP32_PN532INTERFACE_H
