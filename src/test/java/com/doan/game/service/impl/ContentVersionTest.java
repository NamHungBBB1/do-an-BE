package com.doan.game.service.impl;

import com.doan.game.enums.ChangeClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Số hiệu SemVer do BE tính (PR C, 23/10): so theo số, đi lên từ bản lớn nhất, chưa có bản nào → 1.0.0. */
class ContentVersionTest {

    @Test
    void comparesNumericallyNotAsText() {
        assertThat(ContentServiceImpl.compareVersion("1.10.0", "1.9.0")).isPositive();
        assertThat(ContentServiceImpl.compareVersion("2.0.0", "10.0.0")).isNegative();
        assertThat(ContentServiceImpl.compareVersion("1.2.3", "1.2.3")).isZero();
    }

    @Test
    void bumpsFromHighestEverReleased() {
        List<String> versions = List.of("1.0.0", "1.9.0", "1.10.0", "1.1.0");
        assertThat(ContentServiceImpl.nextVersion(versions, ChangeClass.PATCH)).isEqualTo("1.10.1");
        assertThat(ContentServiceImpl.nextVersion(versions, ChangeClass.MINOR)).isEqualTo("1.11.0");
        assertThat(ContentServiceImpl.nextVersion(versions, ChangeClass.MAJOR)).isEqualTo("2.0.0");
        assertThat(ContentServiceImpl.nextVersion(versions, null)).isEqualTo("2.0.0");   // thiếu loại → cao nhất
        assertThat(ContentServiceImpl.nextVersion(List.of(), ChangeClass.PATCH)).isEqualTo("1.0.0");
    }
}
