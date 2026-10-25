package dev.noqui.core;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Only known service codes become player-facing text; raw tool output stays private. */
public final class SubmissionFailure extends RuntimeException {
    public SubmissionFailure(String code) {
        super(switch(code==null?"UNKNOWN":code) {
            case "TOO_LONG" -> "6분 초과 영상 또는 실시간 방송은 등록할 수 없습니다.";
            case "LIVE_UNSUPPORTED" -> "실시간 방송은 등록할 수 없습니다.";
            case "DURATION_UNAVAILABLE" -> "영상 길이를 확인할 수 없습니다.";
            case "TOO_LARGE" -> "음원 또는 처리 데이터의 용량 제한을 초과했습니다.";
            case "FORMAT_UNSUPPORTED" -> "지원하지 않는 음원 형식입니다.";
            case "SOURCE_AGE_RESTRICTED" -> "성인 인증이 필요한 영상은 등록할 수 없습니다.";
            case "SOURCE_AUTH_REQUIRED" -> "YouTube 인증에 실패했습니다. 성인 인증·로그인 제한을 확인하세요.";
            case "SOURCE_BLOCKED" -> "YouTube에서 요청을 차단했습니다. 잠시 후 다시 제출하세요.";
            case "SOURCE_UNAVAILABLE" -> "영상을 불러올 수 없습니다. 링크와 공개 여부를 확인하세요.";
            case "QUEUE_FULL", "RATE_LIMITED" -> "음원 처리 대기열이 가득 찼습니다. 잠시 후 다시 제출하세요.";
            case "INGEST_TIMEOUT", "PREPARATION_TIMEOUT" -> "음원 준비 제한 시간을 초과했습니다.";
            case "INGEST_INTERRUPTED" -> "음원 처리가 중단됐습니다. 다시 제출하세요.";
            case "DISK_FULL", "CACHE_FULL" -> "음원 저장 공간이 부족해 등록하지 못했습니다.";
            case "STORAGE_UNAVAILABLE" -> "음원 정보를 저장하지 못했습니다. 잠시 후 다시 제출하세요.";
            case "INGEST_UNAVAILABLE", "SERVICE_UNAVAILABLE" -> "음원 처리 서비스에 연결할 수 없습니다.";
            case "VERIFY_FAILED" -> "음원 파일 검증에 실패했습니다.";
            case "CACHE_EVICTED", "CACHE_MISSING", "JOB_NOT_FOUND", "MEDIA_NOT_FOUND" -> "음원 작업이나 캐시가 만료됐습니다. 다시 제출하세요.";
            default -> "음원을 준비하지 못했습니다. 잠시 후 다시 제출하세요.";
        });
    }

    public static String reason(Throwable failure) {
        for(int depth=0;depth<8&&(failure instanceof CompletionException||failure instanceof ExecutionException)&&failure.getCause()!=null;depth++)failure=failure.getCause();
        return failure instanceof SubmissionFailure?failure.getMessage():new SubmissionFailure("UNKNOWN").getMessage();
    }
}
