// WebSocket link to the world server: parses the message stream, attaches the
// command token (from the URL hash, /#t=SECRET) to outgoing commands, and
// reconnects with backoff — a phone locking its screen mid-watch just resumes.

import type { Command, ServerMsg } from './protocol';

export type NetStatus = 'connecting' | 'open' | 'closed';

export class Net {
  private ws: WebSocket | null = null;
  private backoff = 500;
  status: NetStatus = 'connecting';

  constructor(
    private onMsg: (m: ServerMsg, receivedAt: number) => void,
    private onStatus: (s: NetStatus) => void,
  ) {}

  private token(): string {
    return new URLSearchParams(location.hash.slice(1)).get('t') ?? '';
  }

  connect(): void {
    const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    this.setStatus('connecting');
    const ws = new WebSocket(proto + location.host + '/api/world/stream');
    this.ws = ws;
    ws.onopen = () => {
      this.backoff = 500;
      this.setStatus('open');
    };
    ws.onmessage = ev => this.onMsg(JSON.parse(ev.data) as ServerMsg, performance.now());
    ws.onclose = () => {
      this.setStatus('closed');
      setTimeout(() => this.connect(), this.backoff);
      this.backoff = Math.min(5000, this.backoff * 2);
    };
  }

  send(cmd: Command): void {
    if (this.ws?.readyState !== WebSocket.OPEN) return;
    const token = this.token();
    this.ws.send(JSON.stringify(token ? { ...cmd, token } : cmd));
  }

  private setStatus(s: NetStatus): void {
    this.status = s;
    this.onStatus(s);
  }
}
