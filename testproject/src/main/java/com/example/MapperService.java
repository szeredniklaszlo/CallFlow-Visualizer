package com.example;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MapperService {

    /**
     * Analyze this method to test Feature 14.
     * 
     * EXPECTED GRAPH:
     * 1. MapperService.mapEntityToDto
     * -> UserEntity.getOrders() [VISIBLE - @OneToMany]
     * -> UserEntity.getBio() [VISIBLE - @Lob]
     * -> UserEntity.getTags() [VISIBLE - @ElementCollection]
     * 
     * HIDDEN NODES (Should NOT appear):
     * -> UserEntity.getUsername()
     * -> UserDto.setUsername()
     * -> UserDto.setBio()
     * -> UserDto.setTags()
     * -> UserDto.setOrderCount()
     */
    @Transactional
    public UserDto mapEntityToDto(UserEntity entity) {
        UserDto dto = new UserDto();

        // 1. Simple getter - SHOULD BE HIDDEN
        // 2. Simple setter - SHOULD BE HIDDEN
        dto.setUsername(entity.getUsername());

        // 3. Risky getter (@Lob / Lazy) - SHOULD BE VISIBLE
        // (Accessing this might trigger a separate SQL query)
        String bio = entity.getBio();
        dto.setBio(bio);

        // 4. Risky getter (@ElementCollection) - SHOULD BE VISIBLE
        dto.setTags(entity.getTags());

        // 5. Risky getter (@OneToMany) - SHOULD BE VISIBLE
        // (Triggering lazy load of orders)
        dto.setOrderCount(entity.getOrders().size());

        return dto;
    }
}
