package com.notification.domain;

/**
 * 외부 호출 1건에 대한 허가.
 *
 * <p><b>permit은 채널이 아니라 호출 한 건에 붙는다.</b> "EMAIL을 허용" 같은 집합 단위 허가를 주면
 * 탐침 1개를 받은 노드가 100건을 한꺼번에 보내버린다.
 *
 * @param generation 발급 시점의 회로 세대. 결과를 반영할 때 이 값이 다르면 그 사이 회로가 다시
 *                   열린 것이므로, <b>늦게 도착한 옛 세대의 성공이 새 차단을 풀지 못한다.</b>
 * @param probeToken HALF_OPEN 탐침으로 발급된 경우의 소유권. 일반 호출이면 null
 */
public record ProviderCallPermit(String scope, long generation, String probeToken) {

    public boolean isProbe() {
        return probeToken != null;
    }
}
