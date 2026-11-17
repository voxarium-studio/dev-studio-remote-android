import { RoomRuntime } from './room-runtime'
import './page.css'

declare global {
  interface Window {
    voxariumBridge?: { postMessage(json: string): void }
    voxariumEditorReceive(input: unknown): void
    voxariumEditorFormat(action: unknown): boolean
    voxariumEditorConfigure(config: unknown): void
  }
}

const editor = document.getElementById('editor')!
const status = document.getElementById('status')!
const text = {
  en: { loading: 'Opening document…', blocked: 'Editing stopped. Your document needs recovery.', error: 'Editor unavailable. Return to Documents to retry.', label: 'Document editor' },
  ru: { loading: 'Открытие документа…', blocked: 'Редактирование остановлено. Требуется восстановление документа.', error: 'Редактор недоступен. Вернитесь к документам и повторите попытку.', label: 'Редактор документа' },
}
let language: 'en' | 'ru' = document.documentElement.lang.startsWith('ru') ? 'ru' : 'en'
let state: 'loading' | 'blocked' | 'error' | 'editing' = 'loading'
function render(): void {
  document.documentElement.lang = language
  editor.setAttribute('aria-label', text[language].label)
  status.textContent = state === 'editing' ? '' : text[language][state]
  status.hidden = state === 'editing'
}
const runtime = new RoomRuntime(editor, event => {
  if (event.event === 'ready') state = 'editing'
  else if (event.event === 'blocked') state = 'blocked'
  else if (event.event === 'disposed') state = 'loading'
  render()
  // The only native capability is a bounded JSON event to the origin-scoped listener.
  // No fallback to addJavascriptInterface, remote pages, fetch or WebSocket.
  if (!window.voxariumBridge) { state = 'error'; render(); throw new Error('Native bridge unavailable') }
  window.voxariumBridge.postMessage(JSON.stringify(event))
})
window.voxariumEditorReceive = input => {
  void runtime.handle(input).catch(() => { state = 'error'; render() })
}
window.voxariumEditorFormat = action => runtime.format(action)
window.voxariumEditorConfigure = input => {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return
  const config = input as { dark?: unknown; language?: unknown; user?: unknown }
  if (typeof config.dark === 'boolean') document.documentElement.classList.toggle('dark', config.dark)
  if (config.language === 'en' || config.language === 'ru') language = config.language
  // Optional identity from native hello, never a key/server/path. Existing hosts may omit it.
  if (config.user && typeof config.user === 'object') {
    const u = config.user as { name?: unknown; color?: unknown }
    if (typeof u.name === 'string' && typeof u.color === 'string') runtime.configureUser({ name: u.name, color: u.color })
  }
  render()
}
// Anchors/pasted content must never navigate the privileged local page.
document.addEventListener('click', event => {
  if ((event.target as Element | null)?.closest?.('a')) event.preventDefault()
}, true)
document.addEventListener('submit', event => event.preventDefault(), true)
window.addEventListener('pagehide', () => runtime.dispose(), { once: true })
render()
