export const SENSOR_ID = import.meta.env.VITE_SENSOR_DEVICE_ID || 'node1_sensor'
export const ACTUATOR_ID = import.meta.env.VITE_ACTUATOR_DEVICE_ID || 'node2_actuator'

export function numeric(value) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value === 'string' && /^-?\d+(\.\d+)?$/.test(value.trim())) {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}
export function binary(value) {
  if ([true, 1, '1', 'ON', 'true'].includes(value)) return true
  if ([false, 0, '0', 'OFF', 'false'].includes(value)) return false
  return null
}
const MODES = ['AUTO', 'MANUAL_ON', 'MANUAL_OFF']
function mode(value) { return MODES.includes(value) ? value : null }
function connection(record) {
  if (!record || typeof record !== 'object') return null
  const direct = binary(record.connected ?? record.online)
  if (direct !== null) return direct
  if (['ONLINE', 'Online', 'online'].includes(record.status)) return true
  if (['OFFLINE', 'Offline', 'offline'].includes(record.status)) return false
  // DB status Active is administrative metadata, not proof that an ESP32 is online.
  return null
}
function recordFor(root, id) {
  const records = Array.isArray(root) ? root : Array.isArray(root?.devices) ? root.devices : []
  const keyed = root?.[id]
  const validKeyed = keyed && typeof keyed === 'object' && !Array.isArray(keyed)
    && (keyed.device_id == null || keyed.device_id === id) ? keyed : null
  return records.find(record => record?.device_id === id) ?? validKeyed ?? (root?.device_id === id ? root : null)
}
export function normalizeState(raw) {
  if (!raw || typeof raw !== 'object') throw new Error('Invalid state payload')
  const root = Array.isArray(raw) ? {} : raw
  // A labelled record belongs only to that device; only an unlabelled flat object can aggregate both.
  const flat = root.device_id == null ? root : {}
  const sensor = recordFor(raw, SENSOR_ID) ?? flat
  const actuator = recordFor(raw, ACTUATOR_ID) ?? flat
  const result = {
    temp: numeric(sensor.temp), humid: numeric(sensor.humid), lux: numeric(sensor.lux), occupancy: binary(sensor.occupancy),
    FAN: binary(actuator.FAN), LIGHT: binary(actuator.LIGHT),
    sensorConnected: connection(recordFor(raw, SENSOR_ID)),
    actuatorConnected: connection(recordFor(raw, ACTUATOR_ID)),
    sensorTimestamp: sensor.timestamp ?? null,
    actuatorTimestamp: actuator.timestamp ?? null,
    auto: binary(actuator.AUTO ?? actuator.auto ?? flat.AUTO ?? flat.auto),
    // Per-channel mode reported by the ESP32 through Node-RED: AUTO | MANUAL_ON | MANUAL_OFF.
    fanMode: mode(actuator.fan_mode), lightMode: mode(actuator.light_mode),
    busy: root.busy === true,
  }
  if (result.auto === null && result.fanMode && result.lightMode) result.auto = result.fanMode === 'AUTO' && result.lightMode === 'AUTO'
  // A labelled device record (even an offline one with null values) is a recognised structure.
  result.recognized = ['temp', 'humid', 'lux', 'occupancy', 'FAN', 'LIGHT'].some(key => result[key] !== null)
    || recordFor(raw, SENSOR_ID) !== null || recordFor(raw, ACTUATOR_ID) !== null
  return result
}
