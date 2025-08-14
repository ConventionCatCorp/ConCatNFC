
#include "leds.h"

#include <stdint.h>
#include <math.h>

 LedState g_ledState = LED_STATE_RAINBOW;
 uint8_t g_r,g_g,g_b;
 led_strip_handle_t g_led_strip;


#define TAG "main"


uint8_t adjustBrightness(uint8_t c){
    return (float(c)) * MAX_BRIGHTNESS;
}

void  configure_led(void)
{
    // LED strip general initialization, according to your led board design
    led_strip_config_t strip_config = {
        .strip_gpio_num = LED_STRIP_GPIO_PIN, // The GPIO that connected to the LED strip's data line
        .max_leds = LED_STRIP_LED_COUNT,      // The number of LEDs in the strip,
        .led_model = LED_MODEL_WS2812,        // LED strip model
        // set the color order of the strip: GRB
        .color_component_format = {
            .format = {
                .r_pos = 1, // red is the second byte in the color data
                .g_pos = 0, // green is the first byte in the color data
                .b_pos = 2, // blue is the third byte in the color data
                .num_components = 3, // total 3 color components
            },
        },
        .flags = {
            .invert_out = false, // don't invert the output signal
        }
    };

    // LED strip backend configuration: SPI
    led_strip_spi_config_t spi_config = {
        .clk_src = SPI_CLK_SRC_DEFAULT, // different clock source can lead to different power consumption
        .spi_bus = SPI2_HOST,           // SPI bus ID
        .flags = {
            .with_dma = true, // Using DMA can improve performance and help drive more LEDs
        }
    };


    ESP_ERROR_CHECK(led_strip_new_spi_device(&strip_config, &spi_config, &g_led_strip));
    ESP_LOGI(TAG, "Created LED strip object with SPI backend");
    return;
}


void led_loop(void *pvParameters)
{

    uint8_t cycler=0;
    while (1) {
        uint16_t delay = 500;
        cycler++;
        switch (g_ledState)
        {
        case LED_STATE_STATIC_COLOR:
            break;
        case LED_STATE_RAINBOW:
            for (int i = 0; i < LED_STRIP_LED_COUNT; i++) {
                hsv aux;
                aux.h = (((i+cycler)%LED_STRIP_LED_COUNT) / (double)LED_STRIP_LED_COUNT)*360;

                aux.s = 1.0;
                aux.v = 1.0;
                rgb col = hsv2rgb(aux);
                ESP_ERROR_CHECK(led_strip_set_pixel(g_led_strip, i, adjustBrightness(col.r * 255), adjustBrightness(col.g * 255), adjustBrightness(col.b * 255)));

            }
            delay = 50;
            break;
        case LED_STATE_ALTERNATING:
            for (int i = 0; i < LED_STRIP_LED_COUNT; i++) {
                if ((i+cycler)%2 == 0){
                    ESP_ERROR_CHECK(led_strip_set_pixel(g_led_strip, i, adjustBrightness(g_r),adjustBrightness(g_g),adjustBrightness(g_b)));
                }else{
                    ESP_ERROR_CHECK(led_strip_set_pixel(g_led_strip, i, 0,0,0));
                }
            }
            break;
        case LED_STATE_PULSATING:
            for (int i = 0; i < LED_STRIP_LED_COUNT; i++) {
                float additionalBrightness = 0.5 + sin( (cycler / 255.0) * (2.0 * M_PI) ) * 0.5;
                ESP_ERROR_CHECK(led_strip_set_pixel(g_led_strip, i, adjustBrightness(g_r * additionalBrightness),adjustBrightness(g_g * additionalBrightness),adjustBrightness(g_b * additionalBrightness)));
            }
            delay = 11;
            break;
        default:
            break;
        }    
        ESP_ERROR_CHECK(led_strip_refresh(g_led_strip));
        vTaskDelay(delay / portTICK_PERIOD_MS); 
    }

    vTaskDelete(NULL);
}


rgb hsv2rgb(hsv HSV)
{
    rgb RGB;
    double H = HSV.h, S = HSV.s, V = HSV.v,
            P, Q, T,
            fract;

    (H == 360.)?(H = 0.):(H /= 60.);
    fract = H - floor(H);

    P = V*(1. - S);
    Q = V*(1. - S*fract);
    T = V*(1. - S*(1. - fract));

    if      (0. <= H && H < 1.)
        RGB = (rgb){.r = V, .g = T, .b = P};
    else if (1. <= H && H < 2.)
        RGB = (rgb){.r = Q, .g = V, .b = P};
    else if (2. <= H && H < 3.)
        RGB = (rgb){.r = P, .g = V, .b = T};
    else if (3. <= H && H < 4.)
        RGB = (rgb){.r = P, .g = Q, .b = V};
    else if (4. <= H && H < 5.)
        RGB = (rgb){.r = T, .g = P, .b = V};
    else if (5. <= H && H < 6.)
        RGB = (rgb){.r = V, .g = P, .b = Q};
    else
        RGB = (rgb){.r = 0., .g = 0., .b = 0.};

    return RGB;
}