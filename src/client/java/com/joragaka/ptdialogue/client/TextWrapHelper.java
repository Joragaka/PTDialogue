package com.joragaka.ptdialogue.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class TextWrapHelper {

    /**
     * Wrap a styled Text into lines not exceeding wrapWidth (measured with TextRenderer.getWidth on OrderedText).
     * This routine preserves styling and does word-based wrapping (words include trailing whitespace).
     */
    public static List<OrderedText> wrapPreserve(TextRenderer textRenderer, Text combined, int wrapWidth) {
        List<MutableText> tokens = tokenize(combined);
        List<OrderedText> lines = new ArrayList<>();

        MutableText currentLine = Text.empty().copy();

        for (MutableText token : tokens) {
            OrderedText tokenOrd = token.asOrderedText();
            int tokenW = textRenderer.getWidth(tokenOrd);
            int lineW = textRenderer.getWidth(currentLine.asOrderedText());

            if (lineW + tokenW <= wrapWidth || currentLine.getString().isEmpty()) {
                // if fits on current line OR current line empty (then force at least one token)
                currentLine.append(token);
            } else {
                // flush current line
                lines.add(currentLine.asOrderedText());
                currentLine = Text.empty().copy();
                // if token itself is wider than wrapWidth, split it
                if (tokenW <= wrapWidth) {
                    currentLine.append(token);
                } else {
                    splitTokenToFit(textRenderer, token, wrapWidth, lines);
                }
            }
        }

        if (!currentLine.getString().isEmpty()) {
            lines.add(currentLine.asOrderedText());
        }

        return lines;
    }

    // Build tokens: sequences of characters where words end with whitespace (so tokens include trailing spaces)
    private static List<MutableText> tokenize(Text t) {
        List<MutableText> tokens = new ArrayList<>();
        final MutableText[] cur = { Text.empty().copy() };

        t.asOrderedText().accept((index, style, codePoint) -> {
            String ch = new String(Character.toChars(codePoint));
            MutableText piece = Text.literal(ch).setStyle(style);
            cur[0].append(piece);
            if (Character.isWhitespace(codePoint)) {
                tokens.add(cur[0]);
                cur[0] = Text.empty().copy();
            }
            return true;
        });

        if (!cur[0].getString().isEmpty()) tokens.add(cur[0]);
        return tokens;
    }

    // Split a single token into fragments that fit into wrapWidth; append fragments to lines list (creating new lines as needed)
    private static void splitTokenToFit(TextRenderer tr, MutableText token, int wrapWidth, List<OrderedText> outLines) {
        final MutableText[] frag = { Text.empty().copy() };
        final int[] fragW = { 0 };

        token.asOrderedText().accept((index, style, codePoint) -> {
            String ch = new String(Character.toChars(codePoint));
            MutableText piece = Text.literal(ch).setStyle(style);
            OrderedText pieceOrd = piece.asOrderedText();
            int pw = tr.getWidth(pieceOrd);
            if (fragW[0] + pw > wrapWidth && !frag[0].getString().isEmpty()) {
                outLines.add(frag[0].asOrderedText());
                frag[0] = Text.empty().copy();
                fragW[0] = 0;
            }
            frag[0].append(piece);
            fragW[0] += pw;
            return true;
        });

        if (!frag[0].getString().isEmpty()) outLines.add(frag[0].asOrderedText());
    }

    // Split a single token into MutableText fragments that fit into wrapWidth.
    // This preserves styles and returns MutableText pieces (not OrderedText.toString()).
    private static void splitTokenToMutable(TextRenderer tr, MutableText token, int wrapWidth, List<MutableText> outFragments) {
        final MutableText[] frag = { Text.empty().copy() };
        final int[] fragW = { 0 };

        token.asOrderedText().accept((index, style, codePoint) -> {
            String ch = new String(Character.toChars(codePoint));
            MutableText piece = Text.literal(ch).setStyle(style);
            OrderedText pieceOrd = piece.asOrderedText();
            int pw = tr.getWidth(pieceOrd);
            if (wrapWidth > 0 && fragW[0] + pw > wrapWidth && !frag[0].getString().isEmpty()) {
                outFragments.add(frag[0]);
                frag[0] = Text.empty().copy();
                fragW[0] = 0;
            }
            frag[0].append(piece);
            fragW[0] += pw;
            return true;
        });

        if (!frag[0].getString().isEmpty()) outFragments.add(frag[0]);
    }

    /**
     * Result of splitting a message into first line (fits into firstLineWidth) and remainder.
     */
    public static class SplitResult {
        public final OrderedText firstLine;
        public final MutableText remainder; // styled
        public SplitResult(OrderedText firstLine, MutableText remainder) {
            this.firstLine = firstLine;
            this.remainder = remainder;
        }
    }

    /**
     * Build the first line (that should be placed after the name) and a styled remainder.
     * This keeps tokens (words with trailing spaces) together when possible.
     */
    public static SplitResult splitFirstLine(TextRenderer tr, Text message, int firstLineWidth) {
        List<MutableText> tokens = tokenize(message);
        MutableText currentLine = Text.empty().copy();
        int currentW = 0;

        MutableText remainder = Text.empty().copy();
        boolean remainderStarted = false;

        for (int i = 0; i < tokens.size(); i++) {
            MutableText token = tokens.get(i);
            OrderedText tokenOrd = token.asOrderedText();
            int tokenW = tr.getWidth(tokenOrd);

            if (!remainderStarted) {
                if (currentW + tokenW <= firstLineWidth || currentLine.getString().isEmpty()) {
                    currentLine.append(token);
                    currentW += tokenW;
                } else {
                    // token doesn't fit on current line; move it (or its part) to remainder
                    // if token itself fits alone on an empty line, then move it whole
                    if (tokenW <= firstLineWidth) {
                        remainder.append(token);
                        remainderStarted = true;
                    } else {
                        // split token: put fragment into current line, rest into remainder
                        int avail = Math.max(0, firstLineWidth - currentW);
                        List<MutableText> mfrags = new ArrayList<>();
                        if (avail > 0) {
                            splitTokenToMutable(tr, token, avail, mfrags);
                        }
                        if (!mfrags.isEmpty()) {
                            // append first fragment to currentLine
                            currentLine.append(mfrags.get(0));
                            // remaining fragments go to remainder
                            for (int f = 1; f < mfrags.size(); f++) {
                                remainder.append(mfrags.get(f));
                            }
                        } else {
                            // if we couldn't split into a fragment for the first line, move whole token to remainder
                            remainder.append(token);
                        }
                        remainderStarted = true;
                    }
                }
            } else {
                remainder.append(token);
            }
        }

        OrderedText firstOrd = currentLine.asOrderedText();
        return new SplitResult(firstOrd, remainder);
    }
}
