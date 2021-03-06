package cn.sunguolei.note.controller;

import cn.sunguolei.note.domain.BaseModel;
import cn.sunguolei.note.domain.User;
import cn.sunguolei.note.service.EmailService;
import cn.sunguolei.note.service.UserService;
import cn.sunguolei.note.utils.DesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Controller
@RequestMapping("/user")
public class UserController {

    private Logger logger = LoggerFactory.getLogger(UserController.class);

    @Value("${yingnote.key}")
    private String KEY_;

    private UserService userService;
    private EmailService emailService;

    public UserController(UserService userService, EmailService emailService) {
        this.userService = userService;
        this.emailService = emailService;
    }
//
//    @GetMapping("/findUserByUsername")
//    public User findUserByUsername(String username) {
//        return userService.findUserByUsername(username);
//    }

    /**
     * 所有用户的列表页，做权限控制，只允许管理员查看
     *
     * @param model 返回前端页面的数据信息
     * @return 返回的对应的模板页面
     */
    @GetMapping("/index")
    public String index(Model model) {
        //查找用户列表
        List<User> userList = userService.index();
        // 将用户列表存放到 model 中，返回给前端页面
        model.addAttribute("userList", userList);

        return "user/index";
    }

    /**
     * 返回注册页面
     *
     * @return 返回注册页面的模板
     */
    @GetMapping("/add")
    public String register() {
        return "user/add";
    }

    /**
     * 创建用户
     *
     * @param user  用户填写的信息
     * @param model 存放返回的提示信息
     * @return 返回对应模板页面
     */
    @PostMapping("/create")
    public String create(User user, Model model) {

        logger.debug("开始创建用户，用户名 - {}", user.getUsername());

        // 1. 先检查用户名和邮箱是否重复，重复的话就不用执行下面的逻辑
        User resultCheckUsername = userService.checkUserByUsername(user.getUsername());
        User resultCheckEmail = userService.checkEmail(user.getEmail());

        if (resultCheckUsername != null) {

            model.addAttribute("msg", "您好，注册失败，该用户名已经注册过，请选择其他用户名，谢谢！");
            logger.debug("用户名重复，用户名 - {}", resultCheckUsername.getUsername());

            return "user/add";

        } else if (resultCheckEmail != null) {

            model.addAttribute("msg", "您好，注册失败，该邮箱已经注册过，请选择其他邮箱，谢谢！");
            logger.debug("邮箱重复，邮箱 - {}", resultCheckEmail.getEmail());

            return "user/add";

        } else {
            // 2. 不重复，再进行用户的创建
            // 用户注册时间
            LocalDateTime createTime = LocalDateTime.now();
            user.setCreateTime(createTime);

            // 加密用户密码
            String password = new BCryptPasswordEncoder(11).encode(user.getPassword());
            user.setPassword(password);

            // 生成随机的 activeCode
            int number = (int) (Math.random() * 90000 + 10000);
            char c = (char) (int) (Math.random() * 26 + 97);
            String code = String.valueOf(number) + c;
            user.setActivateCode(code);

            // 创建用户
            int createResult = userService.create(user);

            // 检查用户是否创建成功
            if (createResult > 0) {
                try {
                    emailService.sendSimpleRegisterMail(user.getEmail(), Locale.CHINA, user);
                    model.addAttribute("msg", "您好，离注册成功就差一步，请到您的邮箱中激活账号，谢谢！");

                    logger.debug("用户注册成功，用户名 - {}", user.getUsername());
                } catch (Exception e) {
                    logger.error("注册成功的邮件发送失败！接受者邮箱 - {}", user.getEmail());
                    e.printStackTrace();
                }
            } else {
                model.addAttribute("msg", "您好，注册失败，发生错误，请联系管理员 yingnote_service@163.com ！\n给您带来的不便深表歉意！");
                logger.error("创建用户，插库失败，用户信息 - {}", user.toString());
            }
        }
        return "user/register";
    }

    /**
     * 检查用户激活邮件中的 code 是否合法
     *
     * @param model 存放返回前端页面的提示信息
     * @param sign  url 中的验证信息，用户名和 code
     * @return 返回验证后的页面，成功或者失败
     * @throws Exception 抛出异常
     */
    @RequestMapping("/activeUser")
    public String checkRegisterCode(Model model, String sign) throws Exception {

        BaseModel baseModel = new BaseModel();

        if (sign == null || sign.length() < 1) {
            baseModel.setReturnCode(101);
            baseModel.setMessage("参数 sign 非法");
        } else {
            // SpringBoot 框架在接受到的 url 参数中会对 + 号处理成空格，这里需要转回来
            sign = sign.replace(" ", "+");
            // 把 url 中的签名解回来
            String decode = DesUtil.decrypt(sign, KEY_);

            String[] userArray = decode.split("_");

            if (userArray.length == 2) {
                // 解析出 username 和 code
                String username = userArray[0];
                String code = userArray[1];

                // 根据 username 和 code ，判断是否合法注册用户
                User userParam = new User();

                userParam.setUsername(username);
                userParam.setActivateStatus(0);
                userParam.setActivateCode(code);
                int count = userService.getUserCountByNameActivateStatus(userParam);

                if (count > 0) {
                    // 如果是合法用户，修改校验标识
                    userParam.setActivateStatus(1);

                    int success = userService.SetUserActivateStatus(userParam);

                    if (success <= 0) {
                        // 更新失败提示
                        baseModel.setReturnCode(104);
                        baseModel.setMessage("激活用户失败 - 设置用户激活标志失败");
                    }
                } else {
                    // 用户不存在或已注册成功，或者非法操作，重复激活等
                    baseModel.setReturnCode(102);
                    baseModel.setMessage("用户不存在或者已注册成功");
                }
            } else {
                // 解密失败，或者可能存在攻击
                baseModel.setReturnCode(103);
                baseModel.setMessage("加密参数格式错误");
            }
        }

        if (baseModel.getReturnCode() > 0) {
            model.addAttribute("msg", baseModel.getMessage());
            return "user/add";
        } else {
            model.addAttribute("msg", "注册成功，请登录");
            return "login";
        }
    }
}