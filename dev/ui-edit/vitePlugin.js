// 开发环境专用的「页面编辑模式」：只在 vite dev 时生效，生产构建不包含任何代码。
// 1. sourceLocator：给 .vue 模板里的每个原生元素加 data-src="文件:行:列"，页面上点到哪块就知道改哪里。
// 2. uiEditPlugin：往页面注入编辑浮层，并把改动记录写到 .claude/ui-edits.json，交给 Claude 同步回源码。
import fs from 'node:fs'
import path from 'node:path'

const ELEMENT = 1
const TEXT = 2
const ATTRIBUTE = 6
const PLAIN_ELEMENT = 0

export function sourceLocator(root) {
  return (node, context) => {
    if (node.type !== ELEMENT || node.tagType !== PLAIN_ELEMENT) return
    const file = path.relative(root, context.filename || '').replace(/\\/g, '/')
    if (!file.startsWith('src/')) return
    if (node.props.some((p) => p.type === ATTRIBUTE && p.name === 'data-src')) return
    const { line, column } = node.loc.start
    node.props.push({
      type: ATTRIBUTE,
      name: 'data-src',
      nameLoc: node.loc,
      value: { type: TEXT, content: `${file}:${line}:${column}`, loc: node.loc },
      loc: node.loc,
    })
  }
}

const ENDPOINT = '/__ui-edits'

function isLocal(req) {
  const ip = req.socket.remoteAddress || ''
  const host = String(req.headers.host || '').split(':')[0]
  return ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(ip) && ['localhost', '127.0.0.1'].includes(host)
}

export function uiEditPlugin() {
  let file = ''
  return {
    name: 'yifangzhi-ui-edit',
    apply: 'serve',
    configResolved(config) {
      file = path.join(config.root, '.claude', 'ui-edits.json')
    },
    transformIndexHtml() {
      return [{ tag: 'script', attrs: { type: 'module', src: '/dev/ui-edit/overlay.js' }, injectTo: 'body' }]
    },
    configureServer(server) {
      server.middlewares.use(ENDPOINT, (req, res) => {
        // 开发服务器监听 0.0.0.0 且允许隧道域名，写文件只接受本机请求。
        if (!isLocal(req)) {
          res.statusCode = 403
          return res.end()
        }
        if (req.method === 'GET') {
          res.setHeader('Content-Type', 'application/json')
          return res.end(fs.existsSync(file) ? fs.readFileSync(file) : '[]')
        }
        if (req.method !== 'PUT') {
          res.statusCode = 405
          return res.end()
        }
        let body = ''
        req.on('data', (chunk) => { body += chunk })
        req.on('end', () => {
          try {
            const edits = JSON.parse(body)
            if (!Array.isArray(edits)) throw new Error('expected an array')
            fs.mkdirSync(path.dirname(file), { recursive: true })
            fs.writeFileSync(file, JSON.stringify(edits, null, 2) + '\n')
            res.statusCode = 204
            res.end()
          } catch (error) {
            res.statusCode = 400
            res.end(String(error.message))
          }
        })
      })
    },
  }
}
