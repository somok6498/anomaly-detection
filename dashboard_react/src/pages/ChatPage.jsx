import { useState, useRef, useEffect, useCallback } from 'react';
import { sendChatMessage } from '../api/apiService';
import { downloadBlob, timestampedFilename } from '../utils/helpers';

const SUGGESTIONS = [
  'How many clients did UPI in last 15 mins?',
  'List all anomaly rules',
  'Show review queue stats',
  'Silenced clients in the system',
  'Clients with shared beneficiaries in last 24 hours',
  'List transactions blocked in last 30 mins',
];

export default function ChatPage({ isFullscreen, onClose, onToggleFullscreen }) {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const endRef = useRef(null);

  useEffect(() => { endRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  const send = useCallback(async (text) => {
    const msg = text || input.trim();
    if (!msg || loading) return;
    setInput('');
    setMessages(prev => [...prev, { role: 'user', text: msg, timestamp: new Date() }]);
    setLoading(true);
    try {
      const result = await sendChatMessage(msg);
      setMessages(prev => [...prev, { role: 'assistant', text: result.summary || '', timestamp: new Date(), result }]);
    } catch (e) {
      setMessages(prev => [...prev, { role: 'assistant', text: `Error: ${e.message}`, timestamp: new Date() }]);
    }
    setLoading(false);
  }, [input, loading]);

  const exportCsv = (result) => {
    if (!result?.columns?.length) return;
    const header = result.columns.join(',');
    const rows = (result.rows || []).map(r => r.join(',')).join('\n');
    downloadBlob(`${header}\n${rows}`, timestampedFilename('chat_export', 'csv'));
  };

  return (
    <div className="chat-container">
      <div className="chat-header">
        <span style={{ fontWeight: 600, fontSize: 14 }}>🤖 AI Assistant</span>
        <div className="flex-row">
          <button className="btn-icon" onClick={onToggleFullscreen} title={isFullscreen ? 'Minimize' : 'Fullscreen'}>
            {isFullscreen ? '⊟' : '⊞'}
          </button>
          <button className="btn-icon" onClick={onClose} title="Close">✕</button>
        </div>
      </div>

      <div className="chat-messages">
        {messages.length === 0 && (
          <div style={{ padding: 16 }}>
            <p className="text-muted text-sm mb-16">Try asking:</p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              {SUGGESTIONS.map(s => (
                <button key={s} className="btn btn-outline btn-sm" style={{ textAlign: 'left', whiteSpace: 'normal' }}
                  onClick={() => send(s)}>{s}</button>
              ))}
            </div>
          </div>
        )}

        {messages.map((m, i) => (
          <div key={i} className={`chat-bubble ${m.role}`}>
            <div>{m.text}</div>
            {m.result?.tabular && m.result.columns?.length > 0 && (
              <div style={{ marginTop: 8, overflowX: 'auto' }}>
                <table className="data-table" style={{ fontSize: 11 }}>
                  <thead><tr>{m.result.columns.map((c, j) => <th key={j}>{c}</th>)}</tr></thead>
                  <tbody>
                    {(m.result.rows || []).map((row, ri) => (
                      <tr key={ri}>{row.map((cell, ci) => <td key={ci}>{cell}</td>)}</tr>
                    ))}
                  </tbody>
                </table>
                <button className="btn btn-outline btn-sm mt-8" onClick={() => exportCsv(m.result)}>Export CSV</button>
              </div>
            )}
            <div className="text-xs text-muted" style={{ marginTop: 4 }}>
              {m.timestamp.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            </div>
          </div>
        ))}

        {loading && <div className="chat-bubble assistant"><em>Thinking...</em></div>}
        <div ref={endRef} />
      </div>

      <div className="chat-input-area">
        <input className="input" placeholder="Ask a question..." value={input}
          onChange={e => setInput(e.target.value)} onKeyDown={e => e.key === 'Enter' && send()} />
        <button className="btn btn-primary btn-sm" onClick={() => send()} disabled={!input.trim() || loading}>Send</button>
      </div>
    </div>
  );
}
