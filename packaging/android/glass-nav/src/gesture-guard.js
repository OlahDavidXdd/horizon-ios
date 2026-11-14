// Recover gestures Android may interrupt without delivering pointerup/cancel.
// Keep the upstream GlassNav in charge of the lens and page selection.
export function guardGestures(root) {
  let active = null;
  function cancel() {
    if (!active) return;
    const previous = active;
    active = null;
    window.dispatchEvent(new PointerEvent('pointercancel', {
      bubbles: true, pointerId: previous.id, pointerType: previous.type, isPrimary: true,
    }));
    if (previous.bar.hasPointerCapture?.(previous.id)) previous.bar.releasePointerCapture(previous.id);
  }
  root.addEventListener('pointerdown', event => {
    if (!event.isPrimary || event.button !== 0) return;
    // A second down also recovers reused pointer IDs after returning to the app.
    cancel();
    const item = event.target.closest('[data-lg-item]');
    const bar = item?.closest('.lg-track');
    if (!bar || !root.contains(bar) || bar.closest('[data-lg-copy]')) return;
    active = {id: event.pointerId, type: event.pointerType, bar};
    try { bar.setPointerCapture(event.pointerId); } catch { /* Synthetic input has no capture. */ }
    if (event.pointerType === 'touch') event.preventDefault();
  }, true);
  const end = event => { if (event.pointerId === active?.id) active = null; };
  window.addEventListener('pointerup', end, true);
  window.addEventListener('pointercancel', end, true);
  window.addEventListener('lostpointercapture', event => {
    if (event.pointerId === active?.id && event.target === active.bar) cancel();
  }, true);
  window.addEventListener('blur', cancel);
  document.addEventListener('visibilitychange', () => { if (document.hidden) cancel(); });
  return cancel;
}
