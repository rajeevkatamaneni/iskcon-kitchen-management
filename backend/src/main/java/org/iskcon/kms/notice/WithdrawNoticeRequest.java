package org.iskcon.kms.notice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Taking a notice down (E9-S1).
 *
 * <p>The reason is required, and that is the only interesting thing about this record. A withdrawal
 * travels the same rails as the notice did, so every temple that saw the original sees the
 * retraction — and a retraction that says only "withdrawn" leaves them worse informed than before,
 * unable to tell a recall that was mistaken from one that is over.
 */
public record WithdrawNoticeRequest(@NotBlank @Size(max = 500) String reason) {
}
