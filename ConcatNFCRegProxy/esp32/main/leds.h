#pragma once

#include "led_strip.h"
#include "esp_log.h"
#include "esp_err.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"


#define LED_STRIP_GPIO_PIN  8
// Numbers of the LED in the strip
#define LED_STRIP_LED_COUNT 32

#define MAX_BRIGHTNESS 0.25

enum LedState {
    LED_STATE_STATIC_COLOR,
    LED_STATE_RAINBOW,
    LED_STATE_ALTERNATING,
    LED_STATE_PULSATING,
    
};


typedef struct {
    double r;       // a fraction between 0 and 1
    double g;       // a fraction between 0 and 1
    double b;       // a fraction between 0 and 1
} rgb;

typedef struct {
    double h;       // angle in degrees
    double s;       // a fraction between 0 and 1
    double v;       // a fraction between 0 and 1
} hsv;


void led_loop(void *pvParameters);
rgb hsv2rgb(hsv in);
void configure_led(void);
uint8_t adjustBrightness(uint8_t c);

