import { useEffect, useRef, useState } from 'react'
import axios from 'axios'
import { commandOutcome, sendCommand } from '../services/dashboardService.js'

export function useControl(refreshState) {
  const [pending, setPending] = useState(null)
  const [result, setResult] = useState(null)
  const busy = useRef(false), mounted = useRef(false), controller = useRef(null)
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; controller.current?.abort() }
  }, [])
  async function control(target, action) {
    if (busy.current || !['FAN', 'LIGHT'].includes(target) || !['ON', 'OFF', 'AUTO'].includes(action)) return
    busy.current = true
    controller.current = new AbortController()
    setPending({ target, action })
    setResult(null)
    try {
      const response = await sendCommand(target, action, controller.current.signal)
      if (mounted.current) setResult({ ...commandOutcome(response.data, target, action), target, action })
    } catch (error) {
      if (!mounted.current || axios.isCancel(error)) return
      let outcome = commandOutcome(error.response?.data, target, action, true)
      if ([400, 403, 404, 409].includes(error.response?.status)) outcome = {
        status: 'FAILURE', tone: error.response.status === 409 ? 'warning' : 'error',
        message: error.response.status === 403 ? 'Tài khoản không có quyền điều khiển.'
          : error.response.status === 404 ? 'Backend chưa tìm thấy thiết bị điều khiển.'
          : error.response.status === 409 ? 'Lệnh chưa được gửi: hệ thống đang xử lý lệnh khác hoặc mạch relay chưa có trạng thái mới. Có thể thử lại sau vài giây.'
          : 'Backend từ chối yêu cầu điều khiển.',
      }
      setResult({ ...outcome, target, action })
    } finally {
      busy.current = false
      if (mounted.current) { setPending(null); refreshState() }
    }
  }
  return { pending, result, control }
}
