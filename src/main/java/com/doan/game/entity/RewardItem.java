package com.doan.game.entity;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một món trong cửa hàng đổi thưởng. Định giá bằng tiền trong game — không bao giờ bằng tiền thật.
 */
@Entity
@Table(name = "reward_item")
@Getter
@Setter
@NoArgsConstructor
public class RewardItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", length = 80)
    private String name;

    @Column(name = "cost")
    private Integer cost;

    @Column(name = "image_url", length = 255)
    private String imageUrl;

    @Column(name = "active")
    private boolean active;
}
