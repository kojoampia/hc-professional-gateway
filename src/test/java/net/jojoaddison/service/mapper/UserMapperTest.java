package net.jojoaddison.service.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jojoaddison.domain.User;
import net.jojoaddison.service.dto.AdminUserDTO;
import net.jojoaddison.service.dto.UserDTO;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UserMapper}.
 */
class UserMapperTest {

    private static final String DEFAULT_LOGIN = "johndoe";
    private static final String DEFAULT_ID = "id1";

    private UserMapper userMapper;
    private User user;
    private AdminUserDTO userDto;

    @BeforeEach
    public void init() {
        userMapper = new UserMapper();
        user = new User();
        user.setLogin(DEFAULT_LOGIN);
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        user.setEmail("johndoe@localhost");
        user.setFirstName("john");
        user.setLastName("doe");
        user.setImageUrl("image_url");
        user.setLangKey("en");

        userDto = new AdminUserDTO(user);
    }

    @Test
    void usersToUserDTOsShouldMapOnlyNonNullUsers() {
        List<User> users = new ArrayList<>();
        users.add(user);
        users.add(null);

        List<UserDTO> userDTOS = userMapper.usersToUserDTOs(users);

        assertThat(userDTOS).isNotEmpty().size().isEqualTo(1);
    }

    @Test
    void userDTOsToUsersShouldMapOnlyNonNullUsers() {
        List<AdminUserDTO> usersDto = new ArrayList<>();
        usersDto.add(userDto);
        usersDto.add(null);

        List<User> users = userMapper.userDTOsToUsers(usersDto);

        assertThat(users).isNotEmpty().size().isEqualTo(1);
    }

    @Test
    void userDTOsToUsersWithAuthoritiesStringShouldMapToUsersWithAuthoritiesDomain() {
        Set<String> authoritiesAsString = new HashSet<>();
        authoritiesAsString.add("ADMIN");
        userDto.setAuthorities(authoritiesAsString);

        List<AdminUserDTO> usersDto = new ArrayList<>();
        usersDto.add(userDto);

        List<User> users = userMapper.userDTOsToUsers(usersDto);

        assertThat(users).isNotEmpty().size().isEqualTo(1);
        assertThat(users.get(0).getAuthorities()).isNotNull();
        assertThat(users.get(0).getAuthorities()).isNotEmpty();
        assertThat(users.get(0).getAuthorities().iterator().next().getName()).isEqualTo("ADMIN");
    }

    @Test
    void userDTOsToUsersMapWithNullAuthoritiesStringShouldReturnUserWithEmptyAuthorities() {
        userDto.setAuthorities(null);

        List<AdminUserDTO> usersDto = new ArrayList<>();
        usersDto.add(userDto);

        List<User> users = userMapper.userDTOsToUsers(usersDto);

        assertThat(users).isNotEmpty().size().isEqualTo(1);
        assertThat(users.get(0).getAuthorities()).isNotNull();
        assertThat(users.get(0).getAuthorities()).isEmpty();
    }

    @Test
    void userDTOToUserMapWithAuthoritiesStringShouldReturnUserWithAuthorities() {
        Set<String> authoritiesAsString = new HashSet<>();
        authoritiesAsString.add("ADMIN");
        userDto.setAuthorities(authoritiesAsString);

        User user = userMapper.userDTOToUser(userDto);

        assertThat(user).isNotNull();
        assertThat(user.getAuthorities()).isNotNull();
        assertThat(user.getAuthorities()).isNotEmpty();
        assertThat(user.getAuthorities().iterator().next().getName()).isEqualTo("ADMIN");
    }

    @Test
    void userDTOToUserMapWithNullAuthoritiesStringShouldReturnUserWithEmptyAuthorities() {
        userDto.setAuthorities(null);

        User user = userMapper.userDTOToUser(userDto);

        assertThat(user).isNotNull();
        assertThat(user.getAuthorities()).isNotNull();
        assertThat(user.getAuthorities()).isEmpty();
    }

    @Test
    void userDTOToUserMapWithNullUserShouldReturnNull() {
        assertThat(userMapper.userDTOToUser(null)).isNull();
    }

    @Test
    void testUserFromId() {
        assertThat(userMapper.userFromId(DEFAULT_ID).getId()).isEqualTo(DEFAULT_ID);
        assertThat(userMapper.userFromId(null)).isNull();
    }

    // ---------------------------------------------------------------- the admin and id-only paths
    //
    // The half of this mapper the existing tests never reached. usersToAdminUserDTOs feeds the admin
    // user listing, and the toDtoId pair is what entity mappers use to reference a user without
    // dragging the whole record along — their null branches are exactly where a mapper fails.

    @Test
    void usersToAdminUserDTOsShouldMapOnlyNonNullUsers() {
        List<User> users = new ArrayList<>();
        users.add(user);
        users.add(null);

        List<AdminUserDTO> dtos = userMapper.usersToAdminUserDTOs(users);

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).getLogin()).isEqualTo(DEFAULT_LOGIN);
        assertThat(dtos.get(0).getEmail()).isEqualTo("johndoe@localhost");
    }

    @Test
    void userToAdminUserDTOCarriesTheAdministrativeFields() {
        AdminUserDTO dto = userMapper.userToAdminUserDTO(user);

        assertThat(dto.getLogin()).isEqualTo(DEFAULT_LOGIN);
        assertThat(dto.getFirstName()).isEqualTo("john");
        assertThat(dto.getLastName()).isEqualTo("doe");
        assertThat(dto.getLangKey()).isEqualTo("en");
        assertThat(dto.isActivated()).isTrue();
    }

    /** The id-only projection: everything but the id is deliberately dropped. */
    @Test
    void toDtoIdKeepsOnlyTheId() {
        user.setId(DEFAULT_ID);

        UserDTO dto = userMapper.toDtoId(user);

        assertThat(dto.getId()).isEqualTo(DEFAULT_ID);
        assertThat(dto.getLogin()).isNull();
    }

    @Test
    void toDtoIdReturnsNullForANullUser() {
        assertThat(userMapper.toDtoId(null)).isNull();
    }

    @Test
    void toDtoIdSetProjectsEveryMember() {
        user.setId(DEFAULT_ID);
        User second = new User();
        second.setId("id2");

        Set<UserDTO> dtos = userMapper.toDtoIdSet(new HashSet<>(Set.of(user, second)));

        assertThat(dtos).extracting(UserDTO::getId).containsExactlyInAnyOrder(DEFAULT_ID, "id2");
    }

    /**
     * A null set becomes an empty one rather than null — the callers iterate the result, so
     * returning null here would move the failure into whichever mapper referenced a user.
     */
    @Test
    void toDtoIdSetReturnsAnEmptySetForNull() {
        assertThat(userMapper.toDtoIdSet(null)).isEmpty();
    }
}
