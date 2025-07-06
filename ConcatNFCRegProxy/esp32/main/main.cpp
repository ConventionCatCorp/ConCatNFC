#define CONFIG_LOG_MASTER_LEVEL ESP_LOG_DEBUG

#include <stdio.h>
#include <stdbool.h>
#include <string.h>
#include <soc/gpio_num.h>
#include <esp_log.h>
#include "driver/spi_common.h"
#include "esp_system.h"
#include "esp_console.h"
#include "esp_vfs_dev.h"
#include "driver/uart.h"
#include "linenoise/linenoise.h"

#include "PN532.h"
#include "PN532InterfaceHSU.h"

#define TAG "main"

static PN532 *nfc;
esp_err_t err;

bool debug_enabled = false;

void debug(const char *fmt, ...) {
    if (debug_enabled) {
        va_list args;
        va_start(args, fmt);
        esp_log_writev(ESP_LOG_INFO, TAG, fmt, args);
        va_end(args);
    }
}
bool setup(void) {
    ESP_LOGI(TAG, "init PN532 in HSU mode");

    PN532Interface *interface = new PN532InterfaceHSU(GPIO_NUM_19, GPIO_NUM_18, GPIO_NUM_NC, GPIO_NUM_NC, UART_NUM_1, 115200);
    nfc = new PN532(interface);

    do {
        err = nfc->m_Interface->pn532_init();
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "failed to initialize PN532");
            nfc->m_Interface->pn532_release();
            vTaskDelay(1000 / portTICK_PERIOD_MS);
        }
    } while(err != ESP_OK);
    printf("init_PN532_SPI success\n");
    return true;
}

bool getFirmwareVersion(void) {
    uint32_t version_data = 0;
    do {
        err = nfc->pn532_get_firmware_version(&version_data);
        if (ESP_OK != err) {
            ESP_LOGI(TAG, "Didn't find PN53x board");
            nfc->m_Interface->pn532_reset();
            vTaskDelay(1000 / portTICK_PERIOD_MS);
        }
    } while (ESP_OK != err);
    // Got ok data, print it out!
    printf("Found chip PN5%02x\n",(uint8_t ) (version_data>>24) & 0xFF);
    printf("Firmware ver. %d", (uint8_t) (version_data>>16) & 0xFF);
    printf(".%d\n", (uint8_t) (version_data>>8) & 0xFF);
    return true;
}

void debug_hex(char *prefix, uint8_t *buffer, uint8_t separator, uint8_t buffer_len) {
    char *string = (char *)malloc(strlen(prefix) + buffer_len * 3 + 1);
    memset(string, 0, strlen(prefix) + buffer_len * 3 + 1);
    sprintf(string, "%s", prefix);
    for (int i = 0; i < buffer_len; i++) {
        sprintf(string + strlen(string), "%02x", buffer[i]);
        if (separator && i < buffer_len - 1) {
            sprintf(string + strlen(string), "%c", separator);
        }
    }
    debug("%s", string);
    free(string);
}

static void initialize_console(void)
{
    /* Disable buffering on stdin and stdout */
    setvbuf(stdin, NULL, _IONBF, 0);
    setvbuf(stdout, NULL, _IONBF, 0);

    /* Minicom, screen, idf_monitor send CR when ENTER key is pressed */
    esp_vfs_dev_uart_port_set_rx_line_endings(CONFIG_ESP_CONSOLE_UART_NUM, ESP_LINE_ENDINGS_CR);
    /* Move the caret to the beginning of the next line on '\n' */
    esp_vfs_dev_uart_port_set_tx_line_endings(CONFIG_ESP_CONSOLE_UART_NUM, ESP_LINE_ENDINGS_CRLF);

    /* Configure UART */
    const uart_config_t uart_config = {
            .baud_rate = CONFIG_ESP_CONSOLE_UART_BAUDRATE,
            .data_bits = UART_DATA_8_BITS,
            .parity = UART_PARITY_DISABLE,
            .stop_bits = UART_STOP_BITS_1,
            .source_clk = UART_SCLK_DEFAULT,
    };
    /* Install UART driver for interrupt-driven reads and writes */
    ESP_ERROR_CHECK( uart_driver_install(UART_NUM_0, 256, 0, 0, NULL, 0) );
    ESP_ERROR_CHECK( uart_param_config(UART_NUM_0, &uart_config) );

    /* Tell VFS to use UART driver */
    esp_vfs_dev_uart_use_driver(CONFIG_ESP_CONSOLE_UART_NUM);

    /* Initialize the console */
    esp_console_config_t console_config = {
            .max_cmdline_length = 256,
            .max_cmdline_args = 8,
#if CONFIG_LOG_COLORS
            .hint_color = atoi(LOG_COLOR_CYAN)
#endif
    };
    ESP_ERROR_CHECK( esp_console_init(&console_config) );

    /* Configure linenoise line completion library */
    linenoiseSetMultiLine(1);
    linenoiseSetCompletionCallback(&esp_console_get_completion);
    linenoiseSetHintsCallback((linenoiseHintsCallback*) &esp_console_get_hint);
    linenoiseHistorySetMaxLen(100);
}

static int wait_for_card(int argc, char **argv)
{

    // Wait for an ISO14443A type cards (Mifare, etc.) with a timeout.
    err = nfc->pn532_auto_poll(PN532_BRTY_ISO14443A_106KBPS, 60000);

    if (err == ESP_OK) {
        debug("Found an ISO14443A card");
    } else {
        debug("Could not find a card.");
    }
    return 0;
}

static int scan_nfc(int argc, char **argv)
{
    uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0 };  // Buffer to store the returned UID
    uint8_t uidLength;                        // Length of the UID (4 or 7 bytes depending on ISO14443A card type)

    debug("Waiting for an ISO14443A Card...");

    // Wait for an ISO14443A type cards (Mifare, etc.) with a timeout.
    err = nfc->pn532_read_passive_target_id(PN532_BRTY_ISO14443A_106KBPS, uid, &uidLength, 1000);

    if (err == ESP_OK) {
        debug("Found an ISO14443A card");
        debug("  UID Length: %d bytes", uidLength);
        debug_hex("  UID Value: ", uid, ':', uidLength);
    } else {
        debug("Could not find a card.");
    }
    return 0;
}

static int toggle_debug(int argc, char **argv) {
    if (debug_enabled) {
        debug_enabled = false;
    } else {
        debug_enabled = true;
        debug("Debug enabled");
    }
    return 0;
}

static void register_nfc_scan(void)
{
    const esp_console_cmd_t cmd = {
            .command = "scan",
            .help = "Scan for a NFC tag",
            .hint = NULL,
            .func = &scan_nfc,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd));
    const esp_console_cmd_t cmd2 = {
            .command = "wait",
            .help = "Wait for an NFC tag",
            .hint = NULL,
            .func = &wait_for_card,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd2));
    const esp_console_cmd_t cmd3 = {
            .command = "debug",
            .help = "Toggles debug mode",
            .hint = NULL,
            .func = &toggle_debug,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd3));
}

extern "C" void app_main(void) {
    setup();
    getFirmwareVersion();

    initialize_console();

    /* Register commands */
    esp_console_register_help_command();
    register_nfc_scan();

    const char* prompt = "nfc> ";
    printf("\nNFC reader console\n");
    printf("Type 'help' to get the list of commands.\n");
    printf("Type 'scan' to scan for a card.\n\n");

    /* Main loop */
    while(true) {
        char* line = linenoise(prompt);
        if (line == NULL) { /* Break on EOF or error */
            continue;
        }
        linenoiseHistoryAdd(line);

        /* Try to run the command */
        int ret;
        esp_err_t err_console = esp_console_run(line, &ret);
        if (err_console == ESP_ERR_NOT_FOUND) {
            printf("Unrecognized command\n");
        } else if (err_console == ESP_ERR_INVALID_ARG) {
            // command was found, but the arguments were incorrect
        } else if (err_console == ESP_OK && ret != ESP_OK) {
            printf("Command returned non-zero error code: 0x%x (%s)\n", ret, esp_err_to_name(ret));
        } else if (err_console != ESP_OK) {
            printf("Internal error: %s\n", esp_err_to_name(err_console));
        }
        linenoiseFree(line);
    }
}