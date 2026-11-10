import { LoaderCircle, Power, Workflow } from 'lucide-react'
import DeviceStatus from './DeviceStatus.jsx'

export default function DeviceControl({ target, state, stale, pending, control }) {
  const value = state?.[target] ?? null
  const mode = state?.[target === 'FAN' ? 'fanMode' : 'lightMode'] ?? null
  const disabled = Boolean(pending) || stale || value === null || state?.actuatorConnected === false
  return (
    <article className="device-control" aria-label={`Điều khiển ${target}`}>
      <DeviceStatus target={target} value={value} connected={state?.actuatorConnected} timestamp={state?.actuatorTimestamp} stale={stale} />
      <div className="device-actions">
        {['ON', 'OFF'].map(action => <button key={action} type="button" aria-label={`${action === 'ON' ? 'Bật' : 'Tắt'} ${target}`} disabled={disabled || (action === 'ON' ? value === true : value === false)}
          className={`device-action ${action === 'ON' ? 'action-on' : ''}`} onClick={() => control(target, action)}>
          {pending?.target === target && pending.action === action ? <LoaderCircle size={15} className="spin" aria-hidden="true" /> : <Power size={14} aria-hidden="true" />}
          {pending?.target === target && pending.action === action ? 'Đang gửi…' : action === 'ON' ? 'Bật thiết bị' : 'Tắt thiết bị'}
        </button>)}
      </div>
      {mode !== null && <div className="device-actions device-actions-mode">
        <span className="control-mode">Chế độ: <strong>{mode === 'AUTO' ? 'Tự động' : 'Thủ công'}</strong></span>
        <button type="button" aria-label={`Chuyển ${target} về tự động`} disabled={disabled || mode === 'AUTO'} className="device-action action-auto" onClick={() => control(target, 'AUTO')}>
          {pending?.target === target && pending.action === 'AUTO' ? <LoaderCircle size={15} className="spin" aria-hidden="true" /> : <Workflow size={14} aria-hidden="true" />}
          {pending?.target === target && pending.action === 'AUTO' ? 'Đang gửi…' : 'Chuyển về tự động'}
        </button>
      </div>}
      <p className="control-hint">{pending?.target === target ? 'Đang chờ xác nhận. Không gửi lặp lại lệnh.' : stale ? 'Điều khiển tạm khóa trong khi mất dữ liệu State.' : value === null ? 'Cần trạng thái thiết bị hợp lệ để điều khiển.' : 'Trạng thái chỉ thay đổi khi nhận lại dữ liệu State.'}</p>
    </article>
  )
}
