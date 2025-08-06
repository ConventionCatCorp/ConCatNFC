# ESP32 NFC Reader Application

This code runs the NFC reader component on an ESP32 device connected to a PN532 chip. The library is written for a high-speed UART (HSU) connection, check your datasheet and adjust the pins in main.cpp if your board layout differs.

## Developing

### Windows

This is an abbreviated version of the [ESP-IDF setup guide](https://docs.espressif.com/projects/vscode-esp-idf-extension/en/latest/additionalfeatures/docker-container.html) for Docker Container development in VSCode.

#### Prerequisites

* Install WSL2, preferably Ubuntu (the default).
  * `wsl --install`
* Install usbipd, a tool to make USB devices available inside Linux containers from a Windows host.
  * `winget install usbipd`
* Ensure WSL Ubuntu integration is enabled in Docker for Windows.
  * Settings -> Resources -> WSL Integration -> Enable Ubuntu -> Apply & Restart
* Install workspace recommended extensions. VSCode should prompt you when you open the repo, make sure they're installed.

#### First-time setup

The following steps should be performed in order.

1. Open a terminal in your WSL distro, such as Ubuntu, to make sure it's alive.
2. Attach the USB cable from your computer to the COM usb port of the device.
    * The USB port on the ESP32S3 is not used for programming.
3. In an Windows PowerShell Administrator terminal run the usbipd command to list available USB devices.
    * `usbipd list`
4. Search the list for something like `USB-Enhanced-SERIAL CH343 (COM8)` and take note of the BUSID value.
    * If in doubt you can run `usbipd list` before and after connecting the device to see how the list changes.
    * The BUSID will be something like `2-8`, take note of the number.
5. Capture the USB device for redirection with the `attach` command in the pwsh terminal.
    * `usbipd attach --wsl --busid <THE_BUS_ID_FROM_STEP_3> --auto-attach`
    * This will make the device available in WSL.
6. In the bash terminal run the command `dmesg` and confirm the last few messages indicate a newly attached device.
7. In Visual Studio Code run `File -> Open Workspace From File` and select the `esp32.code-workspace` file in the root of the repo.
8. The VSCode window will restart, opening the esp32 project workspace. You will be prompted to re-open the workspace _in a dev container_, click yes.
    * If you aren't prompted or miss the prompt you can click the `><` icon in the bottom left of VSCode to open the Remote Connection menu, then select `Reopen in container`.
9. Once everything is warmed up you'll be greeted with the ESP-IDF splash screen. Along the bottom menu bar you should be able to switch `/dev/tty` to `/dev/ttyACM0` from the menu.
    * If `/dev/ttyACM0` isn't available the container didn't notice the device on startup.
    * Try clicking the `><` connection icon and re-building the container.
10. Confirm the device can connect by opening the Serial Monitor by clicking the little screen 🖥️ icon.

You can build and flash as normal.

Once you've done the above setup once you can abbreviate the steps:

1. Plug in the USB device BEFORE launching the dev container.
2. Open the workspace, then launch the dev container.

#### Reasons for this setup

* Container removes local system dependencies for development.
* The ESP-IDF seems to require the project files to be at the root of the active VSCode workspace to function correctly.
