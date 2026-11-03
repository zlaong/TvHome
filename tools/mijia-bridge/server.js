#!/usr/bin/env node
/**
 * 家里设备桥（mijia-bridge）
 *
 * 作用：把局域网里能直连的米家/Yeelight 设备收成一个简单 HTTP 接口，给电视桌面用。
 * 现在支持：Yeelight 灯（开关 / 亮度 / 色温 / 模式），走本地 55443 端口，不经过云端。
 * 以后要接米家云或其他品牌，只需在 devices 里加驱动，接口保持不变。
 *
 * 启动：node server.js            （默认 8080）
 * 接口：
 *   GET  /api/home                 全部设备状态
 *   POST /api/device/<id>          {"on":true} / {"brightness":60} / {"ct":4000}
 *   GET  /api/discover             重新扫描局域网
 */

const http = require('http')
const net = require('net')
const dgram = require('dgram')
const fs = require('fs')
const path = require('path')

const PORT = Number(process.env.PORT || 8080)
const CACHE_FILE = path.join(__dirname, 'devices.json')
const POLL_MS = 5000

// ---------------------------------------------------------------- Yeelight

class Yeelight {
  constructor({ id, ip, name, port = 55443 }) {
    this.id = id
    this.ip = ip
    this.port = port
    this.name = name
    this.type = 'light'
    this.online = false
    this.state = { power: 'off', bright: 0, ct: 0, colorMode: 0 }
    this.sock = null
    this.seq = 0
    this.waiters = new Map()
    this.buf = ''
  }

  connect() {
    return new Promise((resolve, reject) => {
      if (this.sock && !this.sock.destroyed) return resolve()
      const sock = net.createConnection({ host: this.ip, port: this.port })
      sock.setEncoding('utf8')
      sock.setTimeout(4000)
      sock.once('connect', () => {
        this.sock = sock
        resolve()
      })
      sock.on('data', (chunk) => this.onData(chunk))
      sock.on('timeout', () => sock.destroy(new Error('timeout')))
      sock.on('error', (err) => {
        this.sock = null
        this.online = false
        reject(err)
      })
      sock.on('close', () => {
        this.sock = null
        for (const w of this.waiters.values()) w.reject(new Error('closed'))
        this.waiters.clear()
      })
    })
  }

  onData(chunk) {
    this.buf += chunk
    let idx
    while ((idx = this.buf.indexOf('\r\n')) >= 0) {
      const line = this.buf.slice(0, idx)
      this.buf = this.buf.slice(idx + 2)
      let msg
      try {
        msg = JSON.parse(line)
      } catch {
        continue
      }
      // 主动上报（订阅 notify 后才有），顺手更新状态
      if (msg.method === 'props') {
        this.applyProps(msg.params || {})
        continue
      }
      const waiter = this.waiters.get(msg.id)
      if (waiter) {
        this.waiters.delete(msg.id)
        msg.error ? waiter.reject(new Error(msg.error.message)) : waiter.resolve(msg.result)
      }
    }
  }

  command(method, params = []) {
    return new Promise(async (resolve, reject) => {
      try {
        await this.connect()
      } catch (e) {
        return reject(e)
      }
      const id = ++this.seq
      this.waiters.set(id, { resolve, reject })
      this.sock.write(JSON.stringify({ id, method, params }) + '\r\n')
      setTimeout(() => {
        if (this.waiters.has(id)) {
          this.waiters.delete(id)
          reject(new Error('no response'))
        }
      }, 4000)
    })
  }

  applyProps(p) {
    if (p.power !== undefined) this.state.power = p.power
    if (p.bright !== undefined) this.state.bright = Number(p.bright)
    if (p.ct !== undefined) this.state.ct = Number(p.ct)
    if (p.color_mode !== undefined) this.state.colorMode = Number(p.color_mode)
    this.online = true
  }

  async refresh() {
    try {
      const r = await this.command('get_prop', ['power', 'bright', 'ct', 'color_mode'])
      this.applyProps({ power: r[0], bright: r[1], ct: r[2], color_mode: r[3] })
    } catch {
      this.online = false
    }
  }

  async apply(patch) {
    if (patch.on !== undefined) {
      await this.command('set_power', [patch.on ? 'on' : 'off', 'smooth', 400])
      this.state.power = patch.on ? 'on' : 'off'
    }
    if (patch.brightness !== undefined) {
      const v = Math.max(1, Math.min(100, Math.round(patch.brightness)))
      await this.command('set_bright', [v, 'smooth', 400])
      this.state.bright = v
    }
    if (patch.ct !== undefined) {
      const v = Math.max(1700, Math.min(6500, Math.round(patch.ct)))
      await this.command('set_ct_abx', [v, 'smooth', 400])
      this.state.ct = v
    }
    await this.refresh()
  }

  toJSON() {
    return {
      id: this.id,
      name: this.name,
      type: this.type,
      kind: 'light',
      online: this.online,
      controllable: true,
      on: this.state.power === 'on',
      brightness: this.state.bright,
      colorTemp: this.state.ct,
      supports: ['on', 'brightness', 'ct'],
    }
  }
}

// ---------------------------------------------------------------- 设备表

const devices = new Map()

function upsert(device) {
  const exist = devices.get(device.id)
  if (exist) {
    exist.ip = device.ip
    if (device.name) exist.name = device.name
    return exist
  }
  const created = new Yeelight(device)
  devices.set(device.id, created)
  return created
}

function loadCache() {
  try {
    const raw = JSON.parse(fs.readFileSync(CACHE_FILE, 'utf8'))
    for (const d of raw.devices || []) upsert(d)
    console.log(`[cache] 载入 ${devices.size} 台设备`)
  } catch {
    /* 首次运行没有缓存，正常 */
  }
}

function saveCache() {
  const data = { devices: [...devices.values()].map((d) => ({ id: d.id, ip: d.ip, name: d.name, type: d.type })) }
  fs.writeFileSync(CACHE_FILE, JSON.stringify(data, null, 2))
}

// ---------------------------------------------------------------- 局域网发现

function discover(timeoutMs = 3000) {
  return new Promise((resolve) => {
    const found = []
    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true })
    const query = Buffer.from(
      'M-SEARCH * HTTP/1.1\r\n' +
        'HOST: 239.255.255.250:1982\r\n' +
        'MAN: "ssdp:discover"\r\n' +
        'ST: wifi_bulb\r\n\r\n'
    )
    sock.on('message', (msg, rinfo) => {
      const text = msg.toString()
      const loc = /Location:\s*yeelight:\/\/([\d.]+):(\d+)/i.exec(text)
      const model = /model:\s*(.+)/i.exec(text)
      // Yeelight 响应里带一个稳定的 id（设备哈希），用它当主键，IP 变了也不会认错设备
      const stable = /^id:\s*(.+)$/im.exec(text)
      if (!loc) return
      const ip = loc[1]
      if (found.some((f) => f.ip === ip)) return
      found.push({
        id: stable ? stable[1].trim() : ip,
        ip,
        port: Number(loc[2]) || 55443,
        name: model ? model[1].trim() : ip,
      })
    })
    sock.bind(() => {
      try {
        sock.setBroadcast(true)
        sock.send(query, 0, query.length, 1982, '239.255.255.250')
      } catch (e) {
        console.error('[discover] 发送失败', e.message)
      }
    })
    setTimeout(() => {
      try {
        sock.close()
      } catch {}
      resolve(found)
    }, timeoutMs)
  })
}

// ---------------------------------------------------------------- HTTP

function send(res, code, body) {
  const json = JSON.stringify(body)
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(json),
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  })
  res.end(json)
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`)

  if (req.method === 'OPTIONS') return send(res, 204, {})

  if (req.method === 'GET' && url.pathname === '/api/home') {
    return send(res, 200, {
      updatedAt: Date.now(),
      devices: [...devices.values()].map((d) => d.toJSON()),
    })
  }

  // 网页界面
  if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/ui.html')) {
    try {
      const html = fs.readFileSync(path.join(__dirname, 'ui.html'))
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
      return res.end(html)
    } catch (e) {
      return send(res, 500, { error: 'ui.html 读取失败：' + e.message })
    }
  }

  if (req.method === 'GET' && url.pathname === '/api/discover') {
    const found = await discover()
    for (const f of found) upsert(f)
    saveCache()
    return send(res, 200, { found, devices: [...devices.values()].map((d) => d.toJSON()) })
  }

  // 改名：改名后写进 devices.json，重启也记得
  const nameMatch = /^\/api\/device\/(.+)\/name$/.exec(url.pathname)
  if (req.method === 'POST' && nameMatch) {
    const dev = devices.get(decodeURIComponent(nameMatch[1]))
    if (!dev) return send(res, 404, { error: '没有这台设备' })
    let raw = ''
    req.on('data', (c) => (raw += c))
    req.on('end', () => {
      try {
        const { name } = JSON.parse(raw || '{}')
        if (!name || !String(name).trim()) return send(res, 400, { error: '名字不能为空' })
        dev.name = String(name).trim().slice(0, 24)
        saveCache()
        send(res, 200, dev.toJSON())
      } catch (e) {
        send(res, 400, { error: e.message })
      }
    })
    return
  }

  const m = /^\/api\/device\/(.+)$/.exec(url.pathname)
  if (req.method === 'POST' && m) {
    const dev = devices.get(decodeURIComponent(m[1]))
    if (!dev) return send(res, 404, { error: '没有这台设备' })
    let body = ''
    req.on('data', (c) => (body += c))
    req.on('end', async () => {
      try {
        const patch = body ? JSON.parse(body) : {}
        await dev.apply(patch)
        send(res, 200, dev.toJSON())
      } catch (e) {
        send(res, 500, { error: e.message })
      }
    })
    return
  }

  send(res, 404, { error: 'not found' })
})

// ---------------------------------------------------------------- 启动

async function main() {
  loadCache()
  if (devices.size === 0) {
    console.log('[discover] 首次启动，扫描局域网…')
    const found = await discover()
    for (const f of found) upsert(f)
    if (devices.size) saveCache()
  }

  await Promise.all([...devices.values()].map((d) => d.refresh()))
  setInterval(() => {
    for (const d of devices.values()) d.refresh()
  }, POLL_MS)

  server.listen(PORT, () => {
    console.log(`[mijia-bridge] http://localhost:${PORT}/api/home  （${devices.size} 台设备）`)
  })
}

main()