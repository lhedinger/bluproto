// The URL flag contract, in one place. Two flag families share the query
// string: PERF flags are default-ON and switched off with `<flag>=0` (each
// names a visual subsystem whose cost can be isolated by A/B-ing two loads),
// while VIEW flags are default-OFF and switched on with `<flag>=1` or bare
// presence (nobody wants a chart over the world until they ask). The ⚙ dialog
// writes these flags and reloads; the renderers read them once at module load.
//
// This module exists because the contract had three hand-rolled regex
// implementations across main.ts and render.ts, and the view-flag one matched
// `?pop=0` as ON — the bare-presence pattern could not tell "asked for" from
// "explicitly declined". URLSearchParams is the parser the browser already
// agrees with; the guard keeps the module loadable where `location` is not a
// thing (the help page's bundler-shared imports, tests).

const params = typeof location !== 'undefined'
  ? new URLSearchParams(location.search)
  : new URLSearchParams();

/** Default-ON perf flag: true only when the URL says `<flag>=0`. */
export const flagOff = (k: string): boolean => params.get(k) === '0';

/** Default-OFF view flag: true for `<flag>=1` or bare `?<flag>`, and NOT for
 *  an explicit `<flag>=0` — declining a panel must never open it. */
export const flagOn = (k: string): boolean =>
  params.has(k) && params.get(k) !== '0';
