package com.doan.game.service.impl;

import com.doan.game.entity.Question;
import com.doan.game.service.impl.QuizSupport.PaperEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** buildPaper (quiz v3, thiết kế mục 4 + 6.3): hoán vị hợp lệ, rút đúng N, keepOptionOrder giữ thứ tự, seed 42 tất định. */
class QuizPaperTest {

    @Test
    void paperIsValidDrawnPermutationAndDeterministicWithSeed() {
        List<Question> questions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Question q = new Question();
            q.setId(UUID.randomUUID());
            q.setKeepOptionOrder(i == 3);
            questions.add(q);
        }
        List<PaperEntry> first = QuizAttemptServiceImpl.buildPaper(questions, 6, new Random(42), q -> 4);
        List<PaperEntry> again = QuizAttemptServiceImpl.buildPaper(questions, 6, new Random(42), q -> 4);
        assertThat(again).isEqualTo(first);   // cùng seed → cùng đề

        assertThat(first).hasSize(6);
        assertThat(new HashSet<>(first.stream().map(PaperEntry::q).toList())).hasSize(6);
        assertThat(questions.stream().map(Question::getId).toList())
                .containsAll(first.stream().map(PaperEntry::q).toList());
        for (PaperEntry entry : first) {
            assertThat(entry.o()).containsExactlyInAnyOrder(0, 1, 2, 3);
            if (entry.q().equals(questions.get(3).getId())) {
                assertThat(entry.o()).containsExactly(0, 1, 2, 3);
            }
        }
        // Không rút (null) → làm hết; thứ tự câu vẫn xáo (seed 42 với 10 câu không ra đúng thứ tự gốc).
        List<PaperEntry> all = QuizAttemptServiceImpl.buildPaper(questions, null, new Random(42), q -> 4);
        assertThat(all).hasSize(10);
        assertThat(all.stream().map(PaperEntry::q).toList())
                .isNotEqualTo(questions.stream().map(Question::getId).toList());
        // Câu keepOptionOrder luôn giữ thứ tự, dù seed nào.
        for (int seed = 0; seed < 20; seed++) {
            for (PaperEntry entry : QuizAttemptServiceImpl.buildPaper(questions, null, new Random(seed), q -> 4)) {
                if (entry.q().equals(questions.get(3).getId())) {
                    assertThat(entry.o()).containsExactly(0, 1, 2, 3);
                }
            }
        }
    }
}
