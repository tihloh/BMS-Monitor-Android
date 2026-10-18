# Battery Monitor telemetry coverage

The monitor is **read-only**. No MOSFET toggles, EEPROM writes, calibration, protection-setting writes, reset, or factory-mode commands are sent.

## Live dashboard
- Pack voltage, signed charge/discharge current, SOC, battery power
- Remaining and BMS-reported capacity
- Approximate remaining runtime or charging time **at the instantaneous current rate**; not a promise or battery protection estimate
- Cell voltage per cell, min/max/average and max-minus-min spread (when provided)
- Last valid status and cell telemetry timestamps; live/stale/awaiting status

## JBD/Xiaoxiang UART-over-BLE FF00
- **Read 0x03**: pack status, BMS cycle count, reported manufacture date, software version, number of cells, charge/discharge MOSFET conducting state, per-cell balancing flags, active protection fault flags, NTC sensor temperatures.
- **Read 0x04**: individual cell voltages.
- **Read 0x05 once on connect**: device name, when the firmware supports a recognizable response.
- 0x03/0x04 are requested periodically; 0x05 is an optional metadata read.
- The monitor validates length, packet framing, checksums, temperature ranges, and only displays extended fields when a valid frame was decoded.
- Temperature sensors are numbered as the BMS reports them; the protocol does not reliably identify which physical component each probe measures.
- Protection flags indicate alarms currently reported by the BMS; they are not the configured voltage/current/temperature threshold values.

## Gentai/ECO (experimentally verified profile)
- **Read 0x21**: voltage, current, SOC, remaining and reported capacity.
- **Read 0x22**: cell voltages.
- Device name is shown when available from Android BLE.
- JBD-only fields are displayed as unavailable, rather than guessing undocumented Gentai packet offsets.

## Standard BLE Battery Service (0x180F)
- Read **0x2A19**: battery level percentage.
- Voltage, cells, faults and other metrics remain unavailable unless a separate supported vendor protocol is detected.

## Compatibility and limitations
A BLE connection alone does not prove that a device's manufacturer-specific telemetry format is compatible. Models with alternate JBD firmware, JK, Daly, ANT and other brands still need documented and verified drivers. Some fields may be absent even within the supported JBD family.

This app shows what a BMS reports. It does not replace hardware protection, verify SOC calibration, or guarantee measurement accuracy.
