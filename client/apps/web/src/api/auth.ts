/** 应用内账号体系（公网 v2）的前端 API。 */
import { request } from './index'

export type AuthMode = 'none' | 'password' | 'token-only'

export interface AuthStatus {
  mode: AuthMode
  authRequired: boolean
  setupRequired: boolean
  password: boolean
  devices: number
  configKey: boolean
  passwordAllowed?: boolean
}

export interface DeviceToken {
  id: string
  name: string
  created: number
  lastUsed: number
  /** 最近一次成功请求的来源 IP（后端按 1 小时节流写入） */
  lastIp?: string
  expiresAt: number
  scope: 'full' | 'read'
  bindIp: string
}

export function getAuthStatus(): Promise<AuthStatus> {
  return request<AuthStatus>('/api/auth/status')
}

export function setupAdmin(body: {
  token: string
  username: string
  password: string
}): Promise<{ ok: boolean; mode: AuthMode }> {
  return request('/api/auth/setup', { method: 'POST', body })
}

export function login(body: {
  username: string
  password: string
}): Promise<{ ok: boolean; user: string; mode: AuthMode }> {
  return request('/api/auth/login', { method: 'POST', body })
}

export function logout(): Promise<{ ok: boolean }> {
  return request('/api/auth/logout', { method: 'POST', body: {} })
}

export interface MeInfo {
  ok: boolean
  user: string
  scope: string
  mode: AuthMode
  /** 设备令牌数量 / 会话数量：改密与"退出所有设备"前给用户看影响面 */
  devices: number
  sessions: number
  /** 当前请求用的是哪个设备令牌（会话为空串） */
  deviceId: string
}

export function me(): Promise<MeInfo> {
  return request('/api/auth/me')
}

/** 安全事件（内存环形，最近 200 条，重启清零）。 */
export interface AuthEvent {
  at: number
  type: string
  detail: string
  ip: string
}

export function listEvents(limit = 50): Promise<{ ok: boolean; events: AuthEvent[] }> {
  return request(`/api/auth/events?limit=${limit}`)
}

/**
 * 退出所有设备：撤销全部设备令牌 + 下线其它浏览器会话，**保留当前会话**。
 */
export function logoutOthers(): Promise<{ ok: boolean; devices: number; sessions: number }> {
  return request('/api/auth/logout-others', { method: 'POST', body: {} })
}

export function changePassword(oldPwd: string, newPwd: string): Promise<{ ok: boolean }> {
  return request('/api/auth/password', { method: 'POST', body: { old: oldPwd, new: newPwd } })
}

export function listDevices(): Promise<{ ok: boolean; devices: DeviceToken[] }> {
  return request('/api/auth/devices')
}

export function createDevice(body: {
  name: string
  scope?: 'full' | 'read'
  expiresInDays?: number
  bindIp?: string
}): Promise<{ ok: boolean; id: string; token: string; name: string; scope: string; expiresAt: number }> {
  return request('/api/auth/devices', { method: 'POST', body })
}

export function revokeDevice(id: string): Promise<{ ok: boolean }> {
  return request(`/api/auth/devices/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function pairStart(): Promise<{ pairId: string; code: string; expiresIn: number }> {
  return request('/api/auth/pair/start', { method: 'POST', body: {} })
}

export function pairApprove(code: string, name: string): Promise<{ ok: boolean }> {
  return request('/api/auth/pair/approve', { method: 'POST', body: { code, name } })
}

export function pairPoll(
  pairId: string,
): Promise<{ approved: boolean; token?: string; deviceId?: string }> {
  return request(`/api/auth/pair/poll?pairId=${encodeURIComponent(pairId)}`)
}

export function rotateConfigKey(): Promise<{ ok: boolean; key: string }> {
  return request('/api/auth/config-key/rotate', { method: 'POST', body: {} })
}
