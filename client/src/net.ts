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

  /** The command token from the URL hash, for the token-gated HTTP endpoints
   *  (the WS path attaches it itself). Empty string when viewing only. */
  get commandToken(): string {
    return this.token();
  }

  /** True when no command token is present: viewing only, no world control. */
  get readOnly(): boolean {
    return this.token() === '';
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
    ws.onmessage = ev => {
      // Instrumented: parsing + applying a herd-sized delta is main-thread
      // work the frame loop never sees — the perf HUD reads these to tell
      // "renderer slow" apart from "stream eating the thread".
      const t0 = performance.now();
      const msg = JSON.parse(ev.data) as ServerMsg;
      this.onMsg(msg, t0);
      const stats = Net.streamStats;
      stats.msgMs += (performance.now() - t0 - stats.msgMs) * 0.2;
      stats.msgKb += (ev.data.length / 1024 - stats.msgKb) * 0.2;
      stats.count++;
    };
    ws.onclose = () => {
      this.setStatus('closed');
      setTimeout(() => this.connect(), this.backoff);
      this.backoff = Math.min(5000, this.backoff * 2);
    };
  }

  /** EMA cost/size of stream messages (parse + state apply), for the HUD. */
  static streamStats = { msgMs: 0, msgKb: 0, count: 0 };

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
