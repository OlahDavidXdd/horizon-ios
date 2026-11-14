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

struct Platform {
    vm: jni::JavaVM,
    class: jni::objects::GlobalRef,
}
static PLATFORM: std::sync::OnceLock<Platform> = std::sync::OnceLock::new();

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_HorizonActivity_initPlatform(
    env: JNIEnv,
    class: JClass,
) {
    if let (Ok(vm), Ok(class)) = (env.get_java_vm(), env.new_global_ref(class)) {
        let _ = PLATFORM.set(Platform { vm, class });
    }
}

pub(super) fn clipboard_text() -> Option<String> {
    let platform = PLATFORM.get()?;
    let mut env = platform.vm.attach_current_thread().ok()?;
    let class = JClass::from(env.new_local_ref(platform.class.as_obj()).ok()?);
    let result = env.call_static_method(class, "readClipboard", "()Ljava/lang/String;", &[]);
    let value = match result {
        Ok(value) => value.l().ok()?,
        Err(_) => {
            let _ = env.exception_clear();
            return None;
        }
    };
    if value.is_null() {
        return None;
    }
    let text = JString::from(value);
    let result = env.get_string(&text).ok()?.into();
    Some(result)
}

pub(super) fn set_clipboard_text(text: &str) {
    let Some(platform) = PLATFORM.get() else {
        return;
    };
    let Ok(mut env) = platform.vm.attach_current_thread() else {
        return;
    };
    let Ok(class) = env.new_local_ref(platform.class.as_obj()) else {
        return;
    };
    let Ok(text) = env.new_string(text) else {
        return;
    };
    if env
        .call_static_method(
            JClass::from(class),
            "writeClipboard",
            "(Ljava/lang/String;)V",
            &[jni::objects::JValue::Object(&text)],
        )
        .is_err()
    {
        let _ = env.exception_clear();
    }
}
