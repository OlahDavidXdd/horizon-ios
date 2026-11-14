//! Nonblocking JNI transport. Only the game thread executes launcher commands.
use jni::{
    JNIEnv,
    objects::{JClass, JString},
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
    super::push(super::Request::new(line.into(), move |answer| {
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
