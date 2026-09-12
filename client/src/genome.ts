// The /help/genome page: everything a lineage inherits, rendered from the
// server's live-constant reference by the renderer all three doc pages share.
import './docs.css';
import { renderMechPage, syncNavPad } from './mechdoc';

void (async () => {
  await renderMechPage('/help/genome.json', document.getElementById('root')!, 'nav-doc');
  syncNavPad();
})();
