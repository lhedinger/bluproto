// An image request that never answers. A phone that freezes a background tab
// can leave a request in flight that neither loads nor fails when the tab
// comes back, and every cache here marks a key as in flight until one of the
// two happens -- so the chunk, the low map or the sprite atlas behind it was
// never asked for again, and the world drew its low map and no sprites for the
// life of the tab. Held requests also sit in the browser's few connections to
// the host, and everything asked after them queues behind.

/** How long an image may stay in flight before it is given up: well past a
 *  phone's slow load of a full screen of chunks, short enough to read as a
 *  hitch and not as a broken world. */
export const STALL_MS = 20000;

/**
 * Gives the request up if it is still in flight after {@link STALL_MS}:
 * cancels the images, so they free their connection and their handlers never
 * run, and calls `forget` so the next frame that wants the key asks again.
 * `waiting` says whether the cache still holds this request's in-flight mark.
 */
export function giveUpIfStalled(imgs: HTMLImageElement[], waiting: () => boolean, forget: () => void): void {
  setTimeout(() => {
    if (!waiting()) return;
    for (const img of imgs) {
      img.onload = null;
      img.onerror = null;
      img.src = '';
    }
    forget();
  }, STALL_MS);
}
