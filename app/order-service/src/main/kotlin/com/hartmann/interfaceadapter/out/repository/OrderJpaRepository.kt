package com.hartmann.interfaceadapter.out.repository

import com.hartmann.interfaceadapter.out.repository.entity.OrderEntity
import org.springframework.data.jpa.repository.JpaRepository

interface OrderJpaRepository : JpaRepository<OrderEntity, Long>
