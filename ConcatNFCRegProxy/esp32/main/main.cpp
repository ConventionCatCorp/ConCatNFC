#define CONFIG_LOG_MASTER_LEVEL ESP_LOG_DEBUG

#include <stdio.h>
#include <string.h>
#include <soc/gpio_num.h>
#include <esp_log.h>
#include "driver/spi_common.h"
#include "esp_system.h"
#include "esp_console.h"
#include "esp_vfs_dev.h"
#include "driver/uart.h"
#include "linenoise/linenoise.h"
#include "esp_log_level.h"

#include "PN532.h"
#include "PN532InterfaceHSU.h"
#include "nfc_operations.h"
#include "ConCatTag.h"
#include <cJSON.h>
#define TAG "main"

static PN532 *nfc;
static ConCatTag *Tags;
esp_err_t err;

bool debug_enabled = false;

bool setup(void) {
    ESP_LOGI(TAG, "init PN532 in HSU mode");

    PN532Interface *interface = new PN532InterfaceHSU(GPIO_NUM_19, GPIO_NUM_18, GPIO_NUM_NC, GPIO_NUM_NC, UART_NUM_1, 115200);
    nfc = new PN532(interface);

    gpio_reset_pin(GPIO_NUM_5);
    gpio_set_direction(GPIO_NUM_5, GPIO_MODE_OUTPUT);

    do {
        err = nfc->m_Interface->pn532_init();
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "failed to initialize PN532");
            nfc->m_Interface->pn532_release();
            vTaskDelay(1000 / portTICK_PERIOD_MS);
        }
    } while(err != ESP_OK);
    Tags = new ConCatTag(nfc);
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

char *bin2hex(uint8_t *buffer, uint8_t buffer_len, char separator) {
    char *string = (char *)malloc(buffer_len * 3 + 1);
    memset(string, 0, buffer_len * 3 + 1);
    for (int i = 0; i < buffer_len; i++) {
        sprintf(string + strlen(string), "%02x", buffer[i]);
        if (separator && i < buffer_len - 1) {
            sprintf(string + strlen(string), "%c", separator);
        }
    }
    return string;
}

void debug_hex(char *prefix, uint8_t *buffer, uint8_t separator, uint8_t buffer_len) {
    char *string = (char *)malloc(strlen(prefix) + buffer_len * 3 + 1);
    memset(string, 0, strlen(prefix) + buffer_len * 3 + 1);
    sprintf(string, "%s", prefix);
    char *hex = bin2hex(buffer, buffer_len, separator);
    sprintf(string + strlen(string), "%s", hex);
    ESP_LOGD(TAG, "%s", string);
    free(string);
    free(hex);
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
    err = nfc->pn532_auto_poll(PN532_BRTY_ISO14443A_106KBPS, 60000, UART_NUM_0);

    if (err == ESP_OK) {
        printf("Detected\n");
    } else {
        printf("Not detected\n");
    }
    return 0;
}

static int uuid(int argc, char **argv)
{
    uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0 };  // Buffer to store the returned UID
    uint8_t uidLength;                        // Length of the UID (4 or 7 bytes depending on ISO14443A card type)

    ESP_LOGD(TAG, "Waiting for an ISO14443A Card...");
    
    // Wait for an ISO14443A type cards (Mifare, etc.) with a timeout.
    err = get_uuid(nfc, uid, &uidLength);

    if (err == ESP_OK) {
        ESP_LOGD(TAG, "Found an ISO14443A card");
        ESP_LOGD(TAG, "  UID Length: %d bytes", uidLength);
        debug_hex("  UID Value: ", uid, ':', uidLength);
        char *hex = bin2hex(uid, uidLength, 0);
        printf("{\"uuid\":\"%s\",\"success\":true}\n", hex);
        free(hex);
    } else {
        ESP_LOGD(TAG, "Could not find a card.");
        printf("{\"success\":false,\"error\":\"Could not find a card\"}\n");
    }
    return 0;
}

static int detect_loop(int argc, char **argv)
{
    while (true){
        uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0 };  // Buffer to store the returned UID
        uint8_t uidLength;                        // Length of the UID (4 or 7 bytes depending on ISO14443A card type)

        err = get_uuid(nfc, uid, &uidLength);

        if (err == ESP_OK) {
            gpio_set_level(GPIO_NUM_5, 1); 
            char *hex = bin2hex(uid, uidLength, 0);
            printf("{\"uuid\":\"%s\",\"success\":true}\n", hex);
            free(hex);
        }else{
            gpio_set_level(GPIO_NUM_5, 0);
        }
        vTaskDelay(200 / portTICK_PERIOD_MS);
        size_t bytesAvailable;
        if (ESP_OK == uart_get_buffered_data_len(UART_NUM_0, &bytesAvailable)) {
            if (bytesAvailable > 0) {
                return 0;
            }
        }
    }
    return 0;
}

static int status_loop(int argc, char **argv) {
    esp_err_t lastErr = 0;
    bool firstStatus = true;
    char *lastStatus = "unknown";
    TickType_t last_status_time = xTaskGetTickCount();
    while (true){
        uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0 };  // Buffer to store the returned UID
        uint8_t uidLength;                        // Length of the UID (4 or 7 bytes depending on ISO14443A card type)

        err = get_uuid(nfc, uid, &uidLength);

        if (err == ESP_OK) {
            if (firstStatus || lastErr != err) {
                printf("Card present\n");
                lastStatus = "Card present";
                last_status_time = xTaskGetTickCount();
            }
        }else{
            if (firstStatus || lastErr != err) {
                printf("Card NOT present\n");
                lastStatus = "Card NOT present";
                last_status_time = xTaskGetTickCount();
            }
        }
        TickType_t now = xTaskGetTickCount();
        if (now - last_status_time > pdMS_TO_TICKS(1000)) {
            printf("%s\n", lastStatus);
            last_status_time = xTaskGetTickCount();
        }
        vTaskDelay(100 / portTICK_PERIOD_MS);
        lastErr = err;
        size_t bytesAvailable;
        firstStatus = false;
        if (ESP_OK == uart_get_buffered_data_len(UART_NUM_0, &bytesAvailable)) {
            if (bytesAvailable > 0) {
                return 0;
            }
        }
    }
    return 0;
}

static int read_tag(int argc, char **argv) {
    if (argc < 2) {
        printf("{\"success\":false,\"error\":\"Not enough arguments\"}\n");
        return 0;
    }
    if (strlen(argv[1]) != 14) {
        printf("{\"success\":false,\"error\":\"Expected UUID length of 14\"}\n");
        return 0;
    }
    uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0 };  // Buffer to store the returned UID
    for (int i = 0; i < 7; i++) {
        unsigned long ul;
        char pByte[3];
        memcpy(pByte, &argv[1][i * 2], 2);
        pByte[2] = '\0';
        ul = strtol(pByte, NULL, 16);
        if (ul == ULONG_MAX || ul > 256) {
            printf("{\"success\":false,\"error\":\"Invalid UUID\"}\n");
            return 0;
        }
        uid[i] = ul;
    }

    uint32_t password;
    returnData ret;

    CardDefinition def;
    if (argc == 3) {
        password = strtoul(argv[2], nullptr, 10);
        ESP_LOGD(TAG, "Password: %d", password);
        ret = read_tag_data(def, Tags, uid, 7, &password);
    } else {
        ret = read_tag_data(def, Tags, uid, 7, NULL);
    }
    if (!ret.success) {
        char buf[256];
        if (!ret.message) {
            sprintf(buf, "Error %d", ret.errorCode);
        } else {
            sprintf(buf, "%s", ret.message);
        }
        printf("{\"success\":false,\"error\":\"%s\"}\n", buf);
        return 0;
    }
    printf("{\"success\":true,\"card\":%s}\n", ret.message);
    def.Free();
    return 0;
}


static int update_tags(int argc, char **argv) {
    if (argc < 3) {
        printf("{\"success\":false,\"error\":\"Not enough arguments. Expected UUID and JSON data\"}\n");
        return 0;
    }

    // Validate UUID
    if (strlen(argv[1]) != 14) {
        printf("{\"success\":false,\"error\":\"Expected UUID length of 14\"}\n");
        return 0;
    }

    uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0 };  // Buffer to store the returned UID
    for (int i = 0; i < 7; i++) {
        unsigned long ul;
        char pByte[3];
        memcpy(pByte, &argv[1][i * 2], 2);
        pByte[2] = '\0';
        ul = strtol(pByte, NULL, 16);
        if (ul == ULONG_MAX || ul > 256) {
            printf("{\"success\":false,\"error\":\"Invalid UUID\"}\n");
            return 0;
        }
        uid[i] = ul;
    }

    uint32_t password = strtoul(argv[2], NULL, 10);

    cJSON *root = cJSON_Parse(argv[3]);
    if (!root) {
        printf("{\"success\":false,\"error\":\"Invalid JSON data\"}\n");
        return 0;
    }

    returnData ret;
    CardDefinition def;

    if (password != 0){
        ret = read_tag_data(def, Tags, uid, 7, &password);
    } else {
        ret = read_tag_data(def, Tags, uid, 7, NULL);
    }
    if (!ret.success) {
        char buf[256];
        snprintf(buf, sizeof(buf), "%s", ret.message ? ret.message : "Unknown error");
        printf("{\"success\":false,\"error\":\"%s\"}\n", buf);
        return 0;
    }

    if (cJSON_HasObjectItem(root, "attendeeId")){
        def.attendee_id = cJSON_GetObjectItem(root, "attendeeId")->valueint;
    }
    if (cJSON_HasObjectItem(root, "conventionId")) {
        def.convention_id = cJSON_GetObjectItem(root, "conventionId")->valueint;
    }
    if (cJSON_HasObjectItem(root, "signature")) {
        const char* sig = cJSON_GetObjectItem(root, "signature")->valuestring;
        strncpy(def.signature, sig, sizeof(def.signature) - 1);
        def.signature[sizeof(def.signature) - 1] = '\0'; // Ensure null-termination
    }
    if (cJSON_HasObjectItem(root, "issuance")) {
        def.issuance = cJSON_GetObjectItem(root, "issuance")->valueint;
    }
    if (cJSON_HasObjectItem(root, "timestamp")) {
        def.timestamp = strtoull(cJSON_GetObjectItem(root, "timestamp")->valuestring, NULL, 10);
    }
    if (cJSON_HasObjectItem(root, "expiration")) {
        def.expiration = strtoull(cJSON_GetObjectItem(root, "expiration")->valuestring, NULL, 10);
    }
    auto tagsNew = def.toTagArray();

    cJSON_Delete(root);


    if (password == 0){
        ret = write_on_card(tagsNew, Tags, uid, 7, nullptr);
    }else{
        ret = write_on_card(tagsNew, Tags, uid, 7, &password);
    }

    if (!ret.success) {
        char buf[256];
        snprintf(buf, sizeof(buf), "%s", ret.message ? ret.message : "Unknown error");
        printf("{\"success\":false,\"error\":\"%s\"}\n", buf);
        return 0;
    }


    ret.message = def.toJSON();
    printf("{\"success\":true,\"card\":%s}\n", ret.message);
    def.Free();
    return 0;
}


static int set_password(int argc, char **argv) {
    // Validate basic arguments
    if (argc < 3) {
        printf("{\"success\":false,\"error\":\"Not enough arguments. Expected UUID and JSON data\"}\n");
        return 0;
    }

    // Validate UUID
    if (strlen(argv[1]) != 14) {
        printf("{\"success\":false,\"error\":\"Expected UUID length of 14\"}\n");
        return 0;
    }


    uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0 };  // Buffer to store the returned UID
    for (int i = 0; i < 7; i++) {
        unsigned long ul;
        char pByte[3];
        memcpy(pByte, &argv[1][i * 2], 2);
        pByte[2] = '\0';
        ul = strtol(pByte, NULL, 16);
        if (ul == ULONG_MAX || ul > 256) {
            printf("{\"success\":false,\"error\":\"Invalid UUID\"}\n");
            return 0;
        }
        uid[i] = ul;
    }

    uint32_t password = strtoul(argv[2], NULL, 10);
    if (password == 0){
        printf("{\"success\":false,\"error\":\"Password cannot be 0\"}\n");
        return 0;
    }

    uint8_t uidAux[] = { 0, 0, 0, 0, 0, 0, 0 };  // Buffer to store the returned UID
    uint8_t uidLength;
    esp_err_t err = get_uuid(Tags->nfc, uidAux, &uidLength);
    if (err != ESP_OK) {
        printf("{\"success\":false,\"error\":\"Card not present\"}\n");
        return 0;
    }
    if ( memcmp(uid, uidAux, uidLength) != 0) {
        printf("{\"success\":false,\"error\":\"UUID Mismatch\"}\n");
        return 0;
    }

    err = set_nfc_password(Tags->nfc, password);
    if (err != ESP_OK) {
        printf("{\"success\":false,\"error\":\"%s\"}\n", "Unknown error");
        return 0;
    }

    printf("{\"success\":true,\"message\":\"Ok\"}\n");
    return 0;
}

static int write_tags(int argc, char **argv) {
    uint8_t nextArg = 1;
    // Validate basic arguments
    if (argc < 3) {
        printf("{\"success\":false,\"error\":\"Not enough arguments. Expected UUID, optional password and JSON data\"}\n");
        return 0;
    }
    
    // Validate UUID
    if (strlen(argv[nextArg++]) != 14) {
        printf("{\"success\":false,\"error\":\"Expected UUID length of 14\"}\n");
        return 0;
    }


    uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0 };  // Buffer to store the returned UID
    for (int i = 0; i < 7; i++) {
        unsigned long ul;
        char pByte[3];
        memcpy(pByte, &argv[1][i * 2], 2);
        pByte[2] = '\0';
        ul = strtol(pByte, NULL, 16);
        if (ul == ULONG_MAX || ul > 256) {
            printf("{\"success\":false,\"error\":\"Invalid UUID\"}\n");
            return 0;
        }
        uid[i] = ul;
    }

    uint32_t password = 0;
    if (argc == 4) {
        password = strtoul(argv[nextArg++], NULL, 10);
    }

    cJSON *root = cJSON_Parse(argv[nextArg++]);
    if (!root) {
        printf("{\"success\":false,\"error\":\"Invalid JSON data\"}\n");
        return 0;
    }

    struct {
        char *name;
        bool required;
        bool present;
    } fields[] = {
            {
                    "attendeeId",
                    true,
                    false,
            },
            {
                    "conventionId",
                    true,
                    false,
            },
            {
                    "issuance",
                    true,
                    false,
            },
            {
                    "timestamp",
                    true,
                    false,
            },
            {
                    "signature",
                    true,
                    false,
            },
    };

    for (auto & field : fields) {
        if (!cJSON_HasObjectItem(root, field.name)) {
            field.present = false;
            if (field.required) {
                printf("{\"success\":false,\"error\":\"Missing required field: %s\"}\n", field.name);
                cJSON_Delete(root);
                return 0;
            }
        } else {
            field.present = true;
        }
    }

    CardDefinition def;

    for (auto & field : fields) {
        if (strcmp(field.name, "attendeeId") == 0 && field.present) {
            def.attendee_id = cJSON_GetObjectItem(root, "attendeeId")->valueint;
        } else if (strcmp(field.name, "conventionId") == 0 && field.present) {
            def.convention_id = cJSON_GetObjectItem(root, "conventionId")->valueint;
        } else if (strcmp(field.name, "issuance") == 0 && field.present) {
            def.issuance = cJSON_GetObjectItem(root, "issuance")->valueint;
        } else if (strcmp(field.name, "timestamp") == 0 && field.present) {
            def.timestamp = strtoull(cJSON_GetObjectItem(root, "timestamp")->valuestring, NULL, 10);
        } else if (strcmp(field.name, "expiration") == 0 && field.present) {
            def.expiration = strtoull(cJSON_GetObjectItem(root, "expiration")->valuestring, NULL, 10);
        } else if (strcmp(field.name, "signature") == 0 && field.present) {
            const char *signature = cJSON_GetObjectItem(root, "signature")->valuestring;
            int errorType = -1;
            ByteArray bytes = Tag::ValidateSignatureStructure((unsigned char *)signature, errorType);
            if (bytes.length == 0) {
                const char *errorMsg = "Unknown signature error";
                switch (errorType) {
                    case 0: errorMsg = "Missing signature"; break;
                    case 1: errorMsg = "Unable to decode base64"; break;
                }
                printf("{\"success\":false,\"error\":\"%s\"}\n", errorMsg);
                cJSON_Delete(root);
                return 0;
            }
            def.signature = cJSON_GetObjectItem(root, "signature")->valuestring;
        }
    }

    TagArray tagsNew = def.toTagArray();

    cJSON_Delete(root);

    returnData ret;
    if (password == 0){
        ret = write_on_card(tagsNew, Tags, uid, 7, nullptr);
    }else{
        ret = write_on_card(tagsNew, Tags, uid, 7, &password);
    }
    if (!ret.success) {
        char buf[256];
        snprintf(buf, sizeof(buf), "%s", ret.message ? ret.message : "Unknown error");
        printf("{\"success\":false,\"error\":\"%s\"}\n", buf);
        return 0;
    }
    
    printf("{\"success\":true,\"card\":%s}\n", ret.message);
    return 0;
}

static int toggle_debug(int argc, char **argv) {
    if (debug_enabled) {
        ESP_LOGD(TAG, "Debug disabled");
        debug_enabled = false;
        esp_log_set_level_master(ESP_LOG_NONE);
        esp_log_level_set(TAG, ESP_LOG_NONE);
        esp_log_level_set("pn532_driver", ESP_LOG_NONE);
        esp_log_level_set("pn532_driver_hsu", ESP_LOG_NONE);
        esp_log_level_set("PN532", ESP_LOG_NONE);
        esp_log_level_set("ConCatTag", ESP_LOG_NONE);
    } else {
        debug_enabled = true;
        esp_log_set_level_master(ESP_LOG_DEBUG);
        esp_log_level_set(TAG, ESP_LOG_DEBUG);
        esp_log_level_set("pn532_driver", ESP_LOG_DEBUG);
        esp_log_level_set("pn532_driver_hsu", ESP_LOG_DEBUG);
        esp_log_level_set("PN532", ESP_LOG_DEBUG);
        esp_log_level_set("ConCatTag", ESP_LOG_DEBUG);
        ESP_LOGD(TAG, "Debug enabled");
    }
    return 0;
}

static void register_nfc_scan(void)
{
    const esp_console_cmd_t cmd = {
            .command = "uuid",
            .help = "Read the UUID of a tag (if present)",
            .hint = NULL,
            .func = &uuid,
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
    const esp_console_cmd_t cmd4 = {
            .command = "read",
            .help = "Reads a tag. First parameter is the UUID in hex. Second parameter is optional, and is the 32 bit password.",
            .hint = NULL,
            .func = &read_tag,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd4));
    const esp_console_cmd_t cmd5 = {
            .command = "detect",
            .help = "keep detecting",
            .hint = NULL,
            .func = &detect_loop,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd5));
    const esp_console_cmd_t cmd6 = {
            .command = "write",
            .help = "write UUID password {json}",
            .hint = NULL,
            .func = &write_tags,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd6));
    const esp_console_cmd_t cmd7 = {
            .command = "status",
            .help = "Monitor card presence continuously",
            .hint = NULL,
            .func = &status_loop,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd7));
    const esp_console_cmd_t cmd8 = {
            .command = "update",
            .help = "update UUID password {json}",
            .hint = NULL,
            .func = &update_tags,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd8));
    const esp_console_cmd_t cmd9 = {
            .command = "set_password",
            .help = "set_password UUID password",
            .hint = NULL,
            .func = &set_password,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd9));
}

extern "C" void app_main(void) {
    setup();
    getFirmwareVersion();

    initialize_console();
    linenoiseSetMultiLine(0); // Simplifies input parsing
    linenoiseSetDumbMode(1);  // Tells linenoise not to expect ANSI escapes

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