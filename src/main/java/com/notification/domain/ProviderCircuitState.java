package com.notification.domain;

/** 제공자 scope의 공유 차단 상태. 노드별 메모리가 아니라 DB 한 행으로 N대가 같은 값을 본다. */
public enum ProviderCircuitState {

    /** 정상. 모든 노드가 호출할 수 있다. */
    CLOSED,

    /** 차단. {@code openUntil}까지 아무도 호출하지 않는다. */
    OPEN,

    /**
     * 탐침 중. <b>클러스터 전체에서 한 건만</b> 호출해 업체가 살아났는지 본다.
     * 여러 노드가 동시에 두드리면 회복 중인 업체를 다시 쓰러뜨린다.
     */
    HALF_OPEN
}
