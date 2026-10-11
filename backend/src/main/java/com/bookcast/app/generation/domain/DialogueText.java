package com.bookcast.app.generation.domain;

import com.bookcast.app.generation.domain.Podcast.*;
import java.util.regex.Pattern;

/** Normalizes newly generated dialogue only; persisted checkpoints must remain unchanged. */
public final class DialogueText {
  private static final Pattern LEADING_ROLE =
      Pattern.compile(
          "\\A\\s*(?:\\*\\*(HOST|EXPERT)\\s*:\\*\\*|\\*\\*(HOST|EXPERT)\\*\\*\\s*[:—-]|(HOST|EXPERT)\\s*[:—-])\\s*",
          Pattern.UNICODE_CHARACTER_CLASS);

  private DialogueText() {}

  public static Segment normalize(Segment segment) {
    return new Segment(
        segment.order(),
        segment.coveredSourceBlockIds(),
        segment.dialogueTurns().stream().map(DialogueText::normalize).toList());
  }

  public static Turn normalize(Turn turn) {
    var prefix = LEADING_ROLE.matcher(turn.text());
    if (!prefix.find()) return turn;
    String role =
        prefix.group(1) != null
            ? prefix.group(1)
            : prefix.group(2) != null ? prefix.group(2) : prefix.group(3);
    if (!turn.speakerRole().name().equals(role)) throw new SpeakerMismatch();
    return new Turn(turn.speakerRole(), turn.text().substring(prefix.end()));
  }

  public static final class SpeakerMismatch extends IllegalArgumentException {
    public SpeakerMismatch() {
      super("DIALOGUE_SPEAKER_PREFIX_MISMATCH");
    }
  }
}
