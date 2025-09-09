#pragma once

// Board output pins
// High-speed UART to NFC module
// NOTE REVERSED UART WIRING:
// TX on MCU to TX on NFC (silkscreened SDA on front)
// RX on MCU to RX on NFC (silkscreened SCL on front)
#define HSU_HOST_RX GPIO_NUM_17
#define HSU_HOST_TX GPIO_NUM_18
#define HSU_UART_PORT UART_NUM_1
#define HSU_BAUD_RATE 115200

// LED config for driving WS2812 LEDs
#define LED_STRIP_GPIO_PIN 14
// Numbers of the LED in the strip
#define LED_STRIP_LED_COUNT 7

#define MAX_BRIGHTNESS 0.5
