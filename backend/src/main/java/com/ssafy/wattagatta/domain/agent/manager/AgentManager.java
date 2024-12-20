package com.ssafy.wattagatta.domain.agent.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.wattagatta.domain.agent.dto.response.AgentDataResponse;
import com.ssafy.wattagatta.domain.agent.dto.response.AgentPositionResponse;
import com.ssafy.wattagatta.domain.agent.dto.response.AgentRouteResponse;
import com.ssafy.wattagatta.domain.agent.model.Agent;
import com.ssafy.wattagatta.domain.agent.model.AgentStatus;
import com.ssafy.wattagatta.domain.agent.model.Constraint;
import com.ssafy.wattagatta.domain.agent.model.ConveyPath;
import com.ssafy.wattagatta.domain.agent.model.Direction;
import com.ssafy.wattagatta.domain.agent.model.Node;
import com.ssafy.wattagatta.domain.agent.service.PathCalcService;
import com.ssafy.wattagatta.domain.agent.utils.PathStore;
import com.ssafy.wattagatta.domain.product.dto.ProductLoc;
import com.ssafy.wattagatta.domain.product.dto.TargetLoc;
import com.ssafy.wattagatta.domain.product.entity.ProductEntity;
import com.ssafy.wattagatta.domain.product.repository.ProductRepository;
import com.ssafy.wattagatta.global.exception.CustomException;
import com.ssafy.wattagatta.global.exception.ErrorCode;
import com.ssafy.wattagatta.global.utils.GlobalClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AgentManager {

    private final List<Agent> agents;
    private final PathStore pathStore;
    private final GlobalClock globalClock;
    private final PathCalcService pathCalcService;
    private final ObjectMapper objectMapper;
    private final WebSocketSessionManager webSocketSessionManager;
    private final ProductRepository productRepository;

    private Agent agent1 = new Agent();
    private Agent agent2 = new Agent();

    @Value("${agent.task.duration.time}")
    private int TASK_DURATION_TIME;

    @Value("${agent.move.duration.time}")
    private int MOVE_DURATION_TIME;

    @Value("${agent.convey.duration.time}")
    private int WAIT_CONVEYOR_TIME;

    @Value("${websocket.unity.path}")
    private String unityPath;

    @Value("${websocket.agent.path}")
    private String agentPath;

    public AgentManager(PathCalcService pathCalcService, GlobalClock globalClock,
                        WebSocketSessionManager webSocketSessionManager, ProductRepository productRepository) {
        this.globalClock = globalClock;
        this.pathCalcService = pathCalcService;
        this.agents = new ArrayList<>();
        this.pathStore = new PathStore();
        objectMapper = new ObjectMapper();
        agent1.ready("agent1", new Node(0, 0, Direction.EAST));
        agent2.ready("agent2", new Node(1, 0, Direction.EAST));
        agents.add(agent1);
        agents.add(agent2);
        this.webSocketSessionManager = webSocketSessionManager;
        this.productRepository = productRepository;
        startBatteryRechargeScheduler();
    }

    public synchronized void assignTaskToAgent(Agent agent, ProductLoc productLoc,
                                               Consumer<ProductLoc> failureCallback) {
        try {
            TargetLoc targetLoc = productLoc.targetLoc();
            int currentGlobalTime = globalClock.getGlobalTime();
            agent.assignTask(targetLoc, currentGlobalTime);

            // 작업 경로 계산
            List<Constraint> constraints = pathStore.getConstraintsForAgent(agent.getId());

            agent.getCurrentNode().setDirection(Direction.EAST);
            ConveyPath conveyPath = calcAgentPathToTarget(agent, constraints);
            List<Node> pathToTarget = conveyPath.getPath();
            int conveyPathSize = conveyPath.getConveyPathSize();

            // 컨베이어 벨트까지 경로(대기 제외)
            List<Node> conveyToPath = conveyPath.getPath().subList(0, conveyPathSize - WAIT_CONVEYOR_TIME);

            // 컨베이어 벨트부터 타겟까지 경로(대기 제외)
            List<Node> conveyToTargetPath = conveyPath.getPath()
                    .subList(conveyPathSize, pathToTarget.size() - TASK_DURATION_TIME);

            // 복귀 경로 계산
            List<Node> returnPath = calcAgentReturnPath(agent, constraints);

            // 타겟부터 집까지 경로 (대기 제외)
            List<Node> targetToHomePath = returnPath.subList(0, returnPath.size() - TASK_DURATION_TIME);

            log.info("에이전트 {}의 경로: {}", agent.getId(), pathToTarget);
            log.info("에이전트 {}의 복귀 경로 : {}", agent.getId(), returnPath);

            List<Node> fullPath = assignFullPathToAgent(agent, pathToTarget, returnPath);

            int requiredBattery = fullPath.size();

            // 배터리가 충분한 경우
            if (agent.getBatteryLevel() > requiredBattery) {
                log.info("배터리 충분 : 현재 agent 배터리 : {} , 필요 배터리 : {}", agent.getBatteryLevel(), requiredBattery);
                pathStore.savePath(agent.getId(), currentGlobalTime, fullPath);

                sendAgentRoute(agent, conveyToPath, conveyToTargetPath, targetToHomePath);

                simulateAgentMovement(agent, fullPath, pathToTarget.size(), conveyPathSize);

                scheduleLoadingCompletion(productLoc, fullPath.size());
            }
            // 배터리가 부족한 경우
            else {
                double rechargeTime = requiredBattery - agent.getBatteryLevel();
                int scheduledTime = (int) (globalClock.getGlobalTime() + 3 + 1);

                // 스케줄러를 사용하여 일정 시간 후에 다시 시도
                log.info("배터리 부족 : 현재 agent 배터리 : {} , 필요 배터리 : {}", agent.getBatteryLevel(), requiredBattery);
                scheduleTaskAssignment(agent, productLoc, failureCallback, scheduledTime);
            }
        } catch (CustomException e) {
            log.error("에이전트 {}가 경로를 찾지 못했습니다: {}", agent.getId(), e.getMessage());
            agent.setStatus(AgentStatus.IDLE);
            failureCallback.accept(productLoc);
        }
    }

    /**
     * 배터리가 부족한 경우 충전 후 다시 실행 메서드
     *
     * @param agent
     * @param productLoc
     * @param failureCallback
     * @param scheduledTime
     */
    private void scheduleTaskAssignment(Agent agent, ProductLoc productLoc, Consumer<ProductLoc> failureCallback,
                                        int scheduledTime) {
        new Thread(() -> {
            try {
                agent.setStatus(AgentStatus.PERFORMING_CHARGE);
                while (globalClock.getGlobalTime() < scheduledTime) {
                    Thread.sleep(1000);
                }
                agent.setCurrentNode(agent.getHomeNode());
                assignTaskToAgent(agent, productLoc, failureCallback);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("작업 할당 재시도 중 인터럽트 발생", e);
            }
        }).start();
    }


    private void simulateAgentMovement(Agent agent, List<Node> fullPath, int pathToTargetSize, int conveyPathSize) {
        new Thread(() -> {
            try {
                Node previousNode = null;
                for (int i = 0; i < fullPath.size(); i++) {
                    log.info("simulate i : {}, fullPath.size : {}", i, fullPath.size());
                    Node currentNode = fullPath.get(i);
                    agent.consumeBattery(1);
                    Thread.sleep(1000);

                    int angle = 1;
                    if (previousNode != null) {
                        angle = calculateAngle(previousNode, currentNode);
                    }

                    boolean arrived = false;
                    boolean conveyArrived = false;
                    boolean houseArrived = false;
                    if (i == pathToTargetSize - (TASK_DURATION_TIME + 1)) {
                        arrived = true;
                    }
                    if (i == fullPath.size() - (TASK_DURATION_TIME + 1)) {
                        houseArrived = true;
                    }
                    if (i == conveyPathSize - (WAIT_CONVEYOR_TIME + 1)) {
                        conveyArrived = true;
                    }

                    sendAgentLocationUpdate(agent, currentNode, angle, arrived, conveyArrived, houseArrived);

                    agent.setCurrentNode(currentNode);
                    previousNode = currentNode;
                }
            } catch (InterruptedException e) {
                log.error("에이전트 이동 시뮬레이션 중 예외 발생", e);
                Thread.currentThread().interrupt();
            } finally {
                agent.setStatus(AgentStatus.IDLE);
                pathStore.removePath(agent.getId());
                notifyAgentAvailable();
                log.info("에이전트 {}가 작업 및 복귀를 완료했습니다.", agent.getId());
            }
        }).start();
    }


    private ConveyPath calcAgentPathToTarget(Agent agent, List<Constraint> constraints) {
        Node originalGoalNode = agent.getGoalNode();
        Node originalCurrentNode = agent.getCurrentNode();

        agent.setGoalNode(agent.getConveyNode());
        List<Node> pathToConvey = pathCalcService.calcPath(agent, constraints, WAIT_CONVEYOR_TIME);

        if (pathToConvey == null) {
            agent.setStatus(AgentStatus.IDLE);
            throw new CustomException(ErrorCode.CANNOT_FIND_NEW_PATH);
        }

        agent.setCurrentNode(agent.getConveyNode());
        agent.getCurrentNode().setDirection(Direction.NORTH);
        agent.setGoalNode(originalGoalNode);
        List<Node> pathFromConveyToGoal = pathCalcService.calcPath(agent, constraints, TASK_DURATION_TIME);
        if (pathFromConveyToGoal == null) {
            agent.setStatus(AgentStatus.IDLE);
            throw new CustomException(ErrorCode.CANNOT_FIND_NEW_PATH);
        }

        if (!pathFromConveyToGoal.isEmpty() && pathFromConveyToGoal.get(0)
                .equals(pathToConvey.get(pathToConvey.size() - 1))) {
            pathFromConveyToGoal.remove(0);
        }

        List<Node> fullPath = new ArrayList<>();
        fullPath.addAll(pathToConvey);
        fullPath.addAll(pathFromConveyToGoal);

        agent.setCurrentNode(originalCurrentNode);

        return new ConveyPath(fullPath, pathToConvey.size());
    }


    private List<Node> calcAgentReturnPath(Agent agent, List<Constraint> constraints) {
        agent.setCurrentNode(agent.getGoalNode());
        agent.setGoalNode(agent.getHomeNode());
        agent.getCurrentNode().setDirection(Direction.SOUTH);
        List<Node> returnPath = pathCalcService.calcPath(agent, constraints, TASK_DURATION_TIME);
        if (returnPath == null) {
            agent.setStatus(AgentStatus.IDLE);
            throw new CustomException(ErrorCode.CANNOT_FIND_NEW_PATH);
        }

        return returnPath;
    }

    private List<Node> assignFullPathToAgent(Agent agent, List<Node> pathToTarget, List<Node> returnPath) {
        List<Node> fullPath = new ArrayList<>(pathToTarget);
        fullPath.addAll(returnPath);
        agent.setCurrentPath(fullPath);
        return fullPath;
    }

    public synchronized Agent findAvailableAgent() {
        return agents.stream()
                .filter(Agent::isAvailable)
                .findFirst()
                .orElse(null);
    }

    public synchronized Agent waitForAvailableAgent() throws InterruptedException {
        Agent agent;
        while ((agent = findAvailableAgent()) == null) {
            wait();
        }
        return agent;
    }

    public synchronized void notifyAgentAvailable() {
        notifyAll();
        log.info("agent 작업 완료 notify All");
    }

    private void sendAgentLocationUpdate(Agent agent, Node nextNode, int angle, boolean arrived,
                                         boolean conveyArrived, boolean houseArrived) {
        try {
            AgentPositionResponse rcCarPosition = new AgentPositionResponse(
                    nextNode.getX(),
                    -2,
                    nextNode.getY()
            );
            AgentDataResponse response = new AgentDataResponse(
                    Integer.parseInt(agent.getId().replace("agent", "")),
                    rcCarPosition,
                    angle,
                    arrived,
                    conveyArrived,
                    houseArrived,
                    (int) agent.getBatteryLevel()
            );

            String jsonMessage = objectMapper.writeValueAsString(response);
//            log.info("Unity 전송 데이터 : {}", jsonMessage);
            if (!webSocketSessionManager.sendMessageToPath(unityPath, jsonMessage)) {
//                log.error("메시지 전송 실패");
            }
        } catch (Exception e) {
//            log.error("에이전트 위치 전송 실패", e);
        }
    }

    /**
     * RC Car 메시지 전송 위한 메서드
     *
     * @param agent
     * @param conveyPath
     * @param targetPath
     * @param homePath
     */
    private void sendAgentRoute(Agent agent, List<Node> conveyPath, List<Node> targetPath, List<Node> homePath) {
        try {
            int carNumber = Integer.parseInt(agent.getId().replace("agent", ""));
            log.info("컨베이어 벨트까지 : {}", conveyPath);
            log.info("적재위치까지 : {}", targetPath);
            log.info("집까지 : {}", homePath);
            List<Integer> routeToConvey = generateRouteActions(conveyPath, Direction.EAST);
            List<Integer> routeToTarget = generateRouteActions(targetPath, Direction.NORTH);
            List<Integer> routeToHome = generateRouteActions(homePath, Direction.SOUTH);

            Direction startingDirectionToConvey = agent.getCurrentDirection();
            Direction startingDirectionToTarget = Direction.NORTH;
            Direction startingDirectionToHome = Direction.SOUTH;

            List<Integer> abRouteToConvey = generateAbsoluteDirections(routeToConvey, startingDirectionToConvey);
            List<Integer> abRouteToTarget = generateAbsoluteDirections(routeToTarget, startingDirectionToTarget);
            List<Integer> abRouteToHome = generateAbsoluteDirections(routeToHome, startingDirectionToHome);

            sendRouteSegment(carNumber, 1, routeToConvey, abRouteToConvey);
            sendRouteSegment(carNumber, 2, routeToTarget, abRouteToTarget);
            sendRouteSegment(carNumber, 3, routeToHome, abRouteToHome);

        } catch (Exception e) {
//            log.error("RCcar 메시지 전송 실패", e);
        }
    }

    private void sendRouteSegment(int carNumber, int goal, List<Integer> routeSegment, List<Integer> abRouteSegment) {
        try {
            AgentRouteResponse response = new AgentRouteResponse(carNumber, goal, routeSegment, abRouteSegment);
            String jsonMessage = objectMapper.writeValueAsString(response);

            if (!webSocketSessionManager.sendMessageToPath(agentPath, jsonMessage)) {
//                log.error("RCcar 메시지 전송 실패");
            }
        } catch (Exception e) {
//            log.error("RCcar 메시지 전송 실패", e);
        }
    }


    private int calculateAngle(Node currentNode, Node nextNode) {
        if (currentNode.getDirection() == nextNode.getDirection()) {
            return 1; // 직진
        } else if (isRightTurn(currentNode.getDirection(), nextNode.getDirection())) {
            return 2; // 우회전
        } else if (isLeftTurn(currentNode.getDirection(), nextNode.getDirection())){
            return 0; // 좌회전
        } else{
            return 4; // 180 도
        }
    }

    private List<Integer> generateRouteActions(List<Node> path, Direction direction) {
        List<Integer> routeActions = new ArrayList<>();

        for (int i = 0; i < path.size() - 1; i++) {
            Node currentNode = path.get(i);
            Node nextNode = path.get(i + 1);

            int action = calculateAction(currentNode, nextNode);
            routeActions.add(action);
        }

        return routeActions;
    }

    private int calculateAction(Node currentNode, Node nextNode) {
        Direction currentDirection = currentNode.getDirection();
        Direction nextDirection = nextNode.getDirection();

        if (currentDirection == nextDirection) {
            if (currentNode.getX() == nextNode.getX() && currentNode.getY() == nextNode.getY()) {
                // x y 방향 같음 => 정지
                return 0;
            }
            // 방향만 같음 => 직진
            else {
                return 1;
            }
        } else if (isLeftTurn(currentDirection, nextDirection)) {
            return 2;
        } else if (isRightTurn(currentDirection, nextDirection)) {
            return 3;
        } else {
            return 0;
        }
    }

    /**
     * IDLE agent 배터리 충전 스케쥴러
     */
    private void startBatteryRechargeScheduler() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            for (Agent agent : agents) {
                if (agent.getStatus() == AgentStatus.PERFORMING_CHARGE) {
                    agent.rechargeBattery(20); // 1초마다 배터리 20씩 충전 3초 완충 상황 가정
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }


    /**
     * Rc car 방향 전환 고려한 경로
     *
     * @param routeSegment
     * @param fullPath
     * @param desiredStartingDirection
     * @param segmentStartIndex
     * @return
     */
    private List<Integer> adjustRoute(
            List<Integer> routeSegment, List<Node> fullPath, Direction desiredStartingDirection,
            int segmentStartIndex) {

        if (routeSegment.isEmpty() || segmentStartIndex >= fullPath.size() - 1) {
            return routeSegment;
        }

        int index = 0;
        Direction currentDirection = desiredStartingDirection;

        for (int i = segmentStartIndex; i < fullPath.size() - 1 && index < routeSegment.size(); i++) {
            Node currentNode = fullPath.get(i);
            Node nextNode = fullPath.get(i + 1);

            Direction requiredDirection = calculateDirection(currentNode, nextNode);

            if (currentDirection != requiredDirection) {
                if (routeSegment.get(index) == 2 || routeSegment.get(index) == 3) {
                    index++;
                    currentDirection = requiredDirection;
                }
                // 방향 전환 명령이 아니면 루프 종료
                else {
                    break;
                }
            }
            // 원하는 방향으로 설정 완료
            else {
                break;
            }
        }

        return routeSegment.subList(index, routeSegment.size());
    }

    /**
     * 절대 방향
     *
     * @param routeSegment
     * @param startingDirection
     * @return
     */
    private List<Integer> generateAbsoluteDirections(List<Integer> routeSegment, Direction startingDirection) {
        List<Integer> abRoute = new ArrayList<>();
        Direction currentDirection = startingDirection;

        for (Integer action : routeSegment) {
            abRoute.add(directionToInt(currentDirection));

            switch (action) {
                case 2:
                    currentDirection = currentDirection.turnLeft();
                    break;
                case 3:
                    currentDirection = currentDirection.turnRight();
                    break;
                default:
                    break;
            }
        }
        return abRoute;
    }

    private void scheduleLoadingCompletion(ProductLoc productLoc, int travelTime) {
        new Thread(() -> {
            try {
                Thread.sleep(travelTime * 1000L);
                ProductEntity product = productRepository.findById(productLoc.productId())
                        .orElseThrow(() -> new CustomException(ErrorCode.CANNOT_FIND_PRODUCT_ENTITY));
                product.changeLoaded();
                productRepository.save(product);
                log.info("Product {} 적재 완료.", productLoc.productId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("적재 처리 중 인터럽트 발생", e);
            } catch (CustomException e) {
                log.error("적재 완료 처리 실패: {}", e.getMessage());
            }
        }).start();
    }


    private int directionToInt(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> -1;
        };
    }


    private Direction calculateDirection(Node fromNode, Node toNode) {
        int dx = toNode.getX() - fromNode.getX();
        int dy = toNode.getY() - fromNode.getY();

        if (dx == 1 && dy == 0) {
            return Direction.NORTH;
        } else if (dx == -1 && dy == 0) {
            return Direction.SOUTH;
        } else if (dx == 0 && dy == 1) {
            return Direction.EAST;
        } else if (dx == 0 && dy == -1) {
            return Direction.WEST;
        }
        return fromNode.getDirection();
    }


    private boolean isLeftTurn(Direction current, Direction next) {
        return (current == Direction.NORTH && next == Direction.WEST)
                || (current == Direction.WEST && next == Direction.SOUTH)
                || (current == Direction.SOUTH && next == Direction.EAST)
                || (current == Direction.EAST && next == Direction.NORTH);
    }

    private boolean isRightTurn(Direction current, Direction next) {
        return (current == Direction.NORTH && next == Direction.EAST)
                || (current == Direction.EAST && next == Direction.SOUTH)
                || (current == Direction.SOUTH && next == Direction.WEST)
                || (current == Direction.WEST && next == Direction.NORTH);
    }


}
