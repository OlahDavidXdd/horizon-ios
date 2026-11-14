//! Android InputDevice snapshots use the same safe controller interface as iOS.
use super::{ConnectedController, Controller, Snapshot};
use jni::{
    JNIEnv,
    objects::{JClass, JFloatArray, JString},
    sys::jint,
};
use std::{
    collections::BTreeMap,
    sync::{LazyLock, Mutex},
};

#[derive(Default)]
struct Pads {
    next: u64,
    active: Option<i32>,
    connected: BTreeMap<i32, ConnectedController>,
}
static PADS: LazyLock<Mutex<Pads>> = LazyLock::new(|| Mutex::new(Pads::default()));

pub(super) fn snapshots() -> Vec<ConnectedController> {
    PADS.lock()
        .unwrap_or_else(|e| e.into_inner())
        .connected
        .values()
        .cloned()
        .collect()
}
pub(super) fn controllers() -> Vec<Controller> {
    snapshots().into_iter().map(|p| p.controller).collect()
}
pub(super) fn active_controller() -> Option<ConnectedController> {
    let pads = PADS.lock().unwrap_or_else(|e| e.into_inner());
    pads.active
        .and_then(|id| pads.connected.get(&id))
        .or_else(|| pads.connected.values().next())
        .cloned()
}
pub(super) fn active() -> Option<Snapshot> {
    active_controller().map(|p| p.state)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_HorizonActivity_controllerChanged(
    mut env: JNIEnv,
    _: JClass,
    device: jint,
    name: JString,
    axes: JFloatArray,
    buttons: jint,
) {
    let Ok(name) = env.get_string(&name) else {
        return;
    };
    let name: String = name.into();
    if env.get_array_length(&axes).ok() != Some(8) {
        return;
    }
    let mut values = [0.0_f32; 8];
    if env.get_float_array_region(&axes, 0, &mut values).is_err() {
        return;
    }
    for value in &mut values {
        if !value.is_finite() {
            *value = 0.0;
        }
        *value = value.clamp(-1.0, 1.0);
    }
    let down = |bit| buttons & (1 << bit) != 0;
    let category = if name.contains("DualSense") || name.contains("DualShock") {
        name.clone()
    } else {
        "Xbox".to_owned()
    };
    let state = Snapshot {
        category: category.clone(),
        a: down(0),
        b: down(1),
        x: down(2),
        y: down(3),
        left_shoulder: down(4),
        right_shoulder: down(5),
        options: down(6),
        menu: down(7),
        left_stick_button: down(8),
        right_stick_button: down(9),
        dpad_up: down(10) || values[7] < -0.5,
        dpad_down: down(11) || values[7] > 0.5,
        dpad_left: down(12) || values[6] < -0.5,
        dpad_right: down(13) || values[6] > 0.5,
        left_stick: [values[0], -values[1]],
        right_stick: [values[2], -values[3]],
        triggers: [values[4].max(0.0), values[5].max(0.0)],
    };
    let mut pads = PADS.lock().unwrap_or_else(|e| e.into_inner());
    let id = if let Some(old) = pads.connected.get(&device) {
        old.id
    } else {
        pads.next += 1;
        pads.next
    };
    pads.connected.insert(
        device,
        ConnectedController {
            id,
            controller: Controller {
                name: Some(name),
                category,
                gamepad: true,
            },
            state,
        },
    );
    pads.active = Some(device);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_HorizonActivity_controllerRemoved(
    _: JNIEnv,
    _: JClass,
    device: jint,
) {
    let mut pads = PADS.lock().unwrap_or_else(|e| e.into_inner());
    pads.connected.remove(&device);
    if pads.active == Some(device) {
        pads.active = None;
    }
}
