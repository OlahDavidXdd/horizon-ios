//! Native-window availability is independent of a minimized/zero-sized drawable.
#[derive(Default)]
pub(crate) struct Lifecycle {
    unavailable: bool,
}
impl Lifecycle {
    pub(crate) fn suspend(&mut self) {
        self.unavailable = true;
    }
    pub(crate) fn defer_resize(&self) -> bool {
        self.unavailable
    }
    pub(crate) fn needs_resume(&self, has_surface: bool, offscreen: bool) -> bool {
        self.unavailable || (!has_surface && !offscreen)
    }
    pub(crate) fn handoff_ready(&self, has_surface: bool, offscreen: bool, drawable: bool) -> bool {
        !self.unavailable && drawable && (has_surface || offscreen)
    }
    pub(crate) fn resumed(&mut self) {
        self.unavailable = false;
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn late_resizes_stay_deferred_until_a_new_native_window_is_attached() {
        let mut state = Lifecycle::default();
        state.suspend();
        // Positive dimensions after TermWindow must not unpause rendering or create an offscreen target.
        for _ in 0..3 {
            assert!(state.defer_resize());
            assert!(state.needs_resume(false, false));
        }
        state.resumed();
        assert!(!state.defer_resize());
        assert!(!state.needs_resume(true, false));
    }
    #[test]
    fn duplicate_resumes_do_not_replace_a_live_swapchain() {
        let state = Lifecycle::default();
        assert!(!state.needs_resume(true, false));
        assert!(state.needs_resume(false, false));
    }
    #[test]
    fn completed_load_waits_for_a_drawable_surface() {
        let mut state = Lifecycle::default();
        assert!(!state.handoff_ready(false, false, true));
        assert!(state.handoff_ready(true, false, true));
        state.suspend();
        assert!(!state.handoff_ready(true, false, true));
        assert!(!state.handoff_ready(false, false, true));
        state.resumed();
        assert!(!state.handoff_ready(true, false, false));
        assert!(state.handoff_ready(true, false, true));
        assert!(state.handoff_ready(false, true, true));
    }
    #[test]
    fn explicit_offscreen_target_survives_a_suspend_resume_cycle() {
        let mut state = Lifecycle::default();
        assert!(!state.needs_resume(false, true));
        state.suspend();
        assert!(state.needs_resume(false, true));
        state.resumed();
        assert!(!state.needs_resume(false, true));
    }
}
