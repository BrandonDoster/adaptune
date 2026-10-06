# Carsifi adapter settings protocol

Captured from Carsifi app 1.6.0 (patched) talking to adapter firmware `1.10.0-406d5ea`.

## Transport

- Bluetooth Classic RFCOMM, service UUID `1d06e9a2-0392-11eb-adc1-0242ac120003` (seen on DLCI 41 / server channel 20; connect by UUID rather than hard-coding the channel).
- Each message is one UTF-8 JSON object. No length prefix and no terminator.
- Replies can arrive split across several RFCOMM frames. Keep buffering until the JSON parses.
- The app connects, sends one command, waits for the reply, then disconnects.
- The same RFCOMM link also carries the standard Android Auto wireless handshake (Wi-Fi credentials) and HFP AT commands on other channels. Ignore those.

## Commands (confirmed)

| Request | Reply |
|---|---|
| `{"command":"info"}` | `{"status":true,"value":{info, ff, paired, aastatus, aasessions}}`, see an example by running the app |
| `{"command":"updateFF","values":{<changed keys only>}}` | `{"status":true,"value":"OK"}` |
| `{"command":"resetFF"}` | `{"status":true,"value":"OK"}`; the "Reset to default settings" button |
| `{"command":"updateOrder","values":["<MAC>",...]}` | `{"status":true,"value":"OK"}`; replaces the paired-phone list with the MACs sent, which is how the official app removes a phone. Reordering isn't supported in the device firmware: the adapter acks any order but doesn't reliably apply it. Each call makes the adapter drop and re-establish its phone connections. |
| `{"command":"syncLogs"}` | `{"status":true,"value":{"version":2,"hasLogs":bool,"logs":[{"filename","content","isPart","index"}]}}`; `content` is base64 of gzip text: the adapter's syslog for the current boot (`session_dmesg.log`, reset on every reboot, including the one a debug-mode change triggers). Sent in chunks of ~3 MB: `isPart:true` with `index` (offset where the chunk ends, e.g. `2999824`) means more is waiting. A small log comes back as `hasLogs:false,isPart:false` with no `index`. Once the log rotates, the reply also carries `session_dmesg_backup0.log` (`isPart:false`, the start of the boot) in full on every sync. |
| `{"command":"eraseLogs","values":[{"filename":"session_dmesg.log","isPart":false,"index":-1}]}` | `{"status":true,"value":"OK"}`; erases the whole log (captured: the official app sends it right after `syncLogs`). To reach the next chunk of a large log, send `{"filename","isPart":true,"index":<index from the chunk>}` to trim what was delivered, then `syncLogs` again (tested: a ~6.8 MB log came back in 3 chunks with no gaps). |

## Settings -> `ff` keys (all confirmed by capture)

| App setting | Keys sent |
|---|---|
| Magic button: Switch AA between paired phones | `swtchCntrNmbr` (int) |
| Magic button: Pause/Resume AA | `psRsmCntrNmbr` (int) |
| Use only 2.4 GHz WIFI + channel + country | `{"is24ghz":true,"cntrCode":"US","htsptChnl24ghz":3}` |
| 5 GHz channel + country | `{"is24ghz":false,"cntrCode":"US","htsptChnl5ghz":40}` |
| Intercept AA protocol | `isAAMim` (bool) |
| Screen DPI (only enabled when Intercept AA is on) | `dpiNumber` (int, 0 = Default), sent together with `isAAMim` |
| Start/Stop features page (Save) | `startStopBsdNthrBlth` (BT-name start/stop), `prstStPsRbt` (persist stop/pause after reboot), `mnlStrtCrsfCnct` (true = auto connect on startup OFF, use Magic button), sent together |
| Debug mode | `debuggable` (bool) |
| Auto USB mode detect | `atUsbDtct` (bool) |
| USB Connection mode | the app sends the whole block `isMtpM`, `usbWtTmt`, `accDrctWthPrpMd`, `isAccSt`, `runGotDeviceNoStart`, `atUsbDtct`, `isAccDr`. Default = `isAccSt:false`, Accessory = `isAccSt:true` |

## Wi-Fi gateway

`{"command":"updateGateway","gateway":{"wifiName":"<ssid>","wifiPassword":"<pw>","wifiBssid":null,"wifi_type":"WIFI_TYPE.WIFI"}}` returns `{"status":true,"value":"OK"}`. Note the key is `gateway`, not `values`, and `wifi_type` here vs `wifiType` in `info.currentGateway` (which reports `device_hotspot` for the adapter's own AP). `wifi_type` values: "Other Wifi" = `WIFI_TYPE.WIFI` (with name/password); "Carsifi Wifi" = `WIFI_TYPE.DEVICE_HOTSPOT` with `wifiName`/`wifiPassword`/`wifiBssid` all `null`, and the adapter fills in its own AP. "Phone Hotspot" could not be captured (the app hangs on its compatibility check). From the app binary's enum strings it is almost certainly `WIFI_TYPE.MOBILE_HOTSPOT` with the phone hotspot's name/password; `info.currentGateway.wifiType` would then read `mobile_hotspot`. Untested. If the adapter can't reach the hotspot, the Bluetooth settings link still works, so send `WIFI_TYPE.DEVICE_HOTSPOT` to revert.

The current gateway, including `wifiPassword`, is in `info.currentGateway`.

## Other command names in the app binary (unconfirmed)

`resetSettings`, `removeDevice`, `addNewWifi`, `updateDefaultGateway`, `enableAutoConnect`, `disableAutoConnect`, `startRecovery`, `sendFirmwareUrl`

## Re-capturing

1. Turn on Developer options > Bluetooth HCI snoop log (Enabled), then toggle Bluetooth off and on.
2. Change settings in the app.
3. `adb bugreport x.zip`, extract `FS/data/misc/bluetooth/logs/btsnoop_hci.log*`.
4. `python3 rfcomm_dump.py --json btsnoop_hci.log.last btsnoop_hci.log` (adapter JSON only, local timestamps, Wi-Fi passwords redacted; drop `--json` for all RFCOMM traffic, add `--full` for untruncated lines)
