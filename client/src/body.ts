// The /help/body page: the body's books and hard constraints, rendered from
// the server's live-constant reference by the renderer all three doc pages
// share.
import './docs.css';
import { renderMechPage, syncNavPad } from './mechdoc';

void (async () => {
  await renderMechPage('/help/body.json', document.getElementById('root')!, 'nav-doc');
  syncNavPad();
})();
