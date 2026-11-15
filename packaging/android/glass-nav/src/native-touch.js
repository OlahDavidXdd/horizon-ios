/** Android owns the complete MotionEvent stream; route it to the actual GlassNav. */
export function nativeTouch(root, cancel) {
  let down = false;
  const send = (target, type, x, y, buttons) => target.dispatchEvent(new PointerEvent(type, {
    bubbles: true, cancelable: true, pointerId: 1, pointerType: 'touch', isPrimary: true,
    button: 0, buttons, clientX: x, clientY: y,
  }));
  return (type, nx, ny) => {
    if (!Number.isFinite(nx) || !Number.isFinite(ny)) return;
    const x = nx * window.innerWidth, y = ny * window.innerHeight;
    if (type === 'cancel') { down = false; cancel(); return; }
    if (type === 'down') {
      down = false; cancel();
      const bar = root.querySelector(':scope > .lg-track');
      if (!bar) return;
      const items = [...bar.querySelectorAll(':scope > [data-lg-item]')];
      const box = bar.getBoundingClientRect();
      // The lifted glass extends beyond its button. Grabbing that visible edge
      // must still start a drag, rather than leaving the lens stuck in place.
      if (x < box.left-14 || x > box.right+14 || y < box.top-14 || y > box.bottom+14) return;
      const index = Math.max(0, Math.min(items.length-1, Math.floor((x-box.left)/box.width*items.length)));
      const item = items[index];
      if (!item) return;
      down = true; send(item, 'pointerdown', x, y, 1);
    } else if (down && type === 'move') send(window, 'pointermove', x, y, 1);
    else if (down && type === 'up') {
      // Final coordinates also cover a fast drag whose MOVE was coalesced by Android.
      send(window, 'pointermove', x, y, 1);
      send(window, 'pointerup', x, y, 0); down = false;
    }
  };
}
