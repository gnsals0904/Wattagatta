package com.pokeypokey.temp.global.utils;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Api 응답 메시지를 정의한 클래스
 *
 * @author 김훈민
 */
public class ApiUtils {

    public static <T> ApiResult<T> success(T response) {
        return new ApiResult<>(ApiStatus.SUCCESS, response, null);
    }

    public static ApiResult<?> error(Throwable throwable, HttpStatus status) {
        return new ApiResult<>(ApiStatus.ERROR, null, new ApiError(throwable, status));
    }

    public static ApiResult<?> error(String message, HttpStatus status) {
        return new ApiResult<>(ApiStatus.ERROR, null, new ApiError(message, status));
    }

    @Getter
    public static class ApiError {

        private final String message;
        private final int status;

        ApiError(Throwable throwable, HttpStatus status) {
            this(throwable.getMessage(), status);
        }

        ApiError(String message, HttpStatus status) {
            this.message = message;
            this.status = status.value();
        }
    }

    @Getter
    public static class ApiResult<T> {

        private final ApiStatus status;
        private final T data;
        private final ApiError error;

        private ApiResult(ApiStatus status, T data, ApiError error) {
            this.status = status;
            this.data = data;
            this.error = error;
        }
    }

    @RequiredArgsConstructor
    enum ApiStatus {

        SUCCESS("성공"),
        ERROR("실패");

        private final String description;
    }

}
