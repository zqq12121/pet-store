// wx.request 使用文本响应收集 SSE，首版在完成后展示；保留来源和动作信息。
function parseReply(text) {
  const answer = { content: '', sources: [], productCards: [], orderCards: [], actions: [], status: 'pending' }
  let done = false
  for (const block of text.replace(/\r\n/g, '\n').split('\n\n')) {
    const lines = block.split('\n'), event = lines.find(l => l.startsWith('event:'))
    const raw = lines.filter(l => l.startsWith('data:')).map(l => l.slice(5).trimStart()).join('\n')
    if (!event || !raw) continue
    const data = JSON.parse(raw), name = event.slice(6).trim()
    if (name === 'meta') answer.id = data.assistantMessageId
    if (name === 'delta') answer.content += data.text || ''
    if (name === 'sources') answer.sources = data.items || []
    if (name === 'cards') { answer.productCards = data.productCards || []; answer.orderCards = data.orderCards || [] }
    if (name === 'action') answer.actions = data.items || []
    if (name === 'error') { const e = new Error(data.message || '智能助手暂时无法回答'); e.retryable = data.retryable; throw e }
    if (name === 'done') { done = true; answer.status = 'completed'; answer.disclaimer = data.disclaimer || 'AI 生成，仅供参考' }
  }
  if (!done) throw new Error('回复连接中断，请重试本次问题')
  return answer
}
module.exports = { parseReply }
