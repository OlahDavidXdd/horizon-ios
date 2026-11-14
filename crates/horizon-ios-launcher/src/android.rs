//! Nonblocking JNI transport. Only the game thread executes launcher commands.
use jni::{
    JNIEnv,
    objects::{JClass, JFloatArray, JString},
    sys::{jboolean, jlong, jstring},
};
use std::{
    collections::HashMap,
    sync::{
        LazyLock, Mutex,
        atomic::{AtomicI64, Ordering},
    },
};

static NEXT: AtomicI64 = AtomicI64::new(1);
static REPLIES: LazyLock<Mutex<HashMap<i64, Option<String>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_HorizonActivity_submit(
    mut env: JNIEnv,
    _: JClass,
    line: JString,
) -> jlong {
    let Ok(line) = env.get_string(&line) else {
        return 0;
    };
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    REPLIES
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(id, None);
    super::push(super::Request::new(line, move |answer| {
        if let Some(slot) = REPLIES
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get_mut(&id)
        {
            *slot = Some(answer.to_owned());
        }
    }));
    id
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_HorizonActivity_poll(
    env: JNIEnv,
    _: JClass,
    id: jlong,
) -> jstring {
    let mut replies = REPLIES.lock().unwrap_or_else(|e| e.into_inner());
    if !replies.get(&id).is_some_and(Option::is_some) {
        return std::ptr::null_mut();
    }
    let answer = replies.remove(&id).flatten().unwrap_or_default();
    env.new_string(answer)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_HorizonActivity_abandon(
    _: JNIEnv,
    _: JClass,
    id: jlong,
) {
    REPLIES
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .remove(&id);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_HorizonActivity_isCovered(
    _: JNIEnv,
    _: JClass,
) -> jboolean {
    u8::from(super::covered())
}

static TOUCH: Mutex<([f32; 100], u32)> = Mutex::new(([f32::NAN; 100], 0));

pub(super) fn touch_preferences() -> ([f32; 100], u32) {
    *TOUCH.lock().unwrap_or_else(|e| e.into_inner())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_HorizonActivity_setTouchPreferences(
    env: JNIEnv,
    _: JClass,
    values: JFloatArray,
    flags: jni::sys::jint,
) {
    if env.get_array_length(&values).ok() != Some(100) {
        return;
    }
    let mut snapshot = [f32::NAN; 100];
    if env
        .get_float_array_region(&values, 0, &mut snapshot)
        .is_err()
    {
        return;
    }
    for value in &mut snapshot {
        if !value.is_nan() && (!value.is_finite() || !(0.0..=1.0).contains(value)) {
            *value = f32::NAN;
        }
    }
    *TOUCH.lock().unwrap_or_else(|e| e.into_inner()) = (snapshot, flags as u32 & 63);
}

pub(super) static KEYBOARD: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_HorizonActivity_keyboardWanted(
    _: JNIEnv,
    _: JClass,
) -> jboolean {
    u8::from(KEYBOARD.load(Ordering::Relaxed))
}
