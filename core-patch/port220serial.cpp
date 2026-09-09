/*
 *  Port220 serial backend for DOSBox-X. See port220serial.h for why this
 *  exists rather than using the built-in nullmodem.
 */

#include "dosbox.h"

#include "setup.h"
#include "serialport.h"
#include "port220serial.h"
#include "logging.h"

#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

/* Poll interval in milliseconds of emulated time. 1ms matches what nullmodem
 * uses for its own polling event and is well below one byte time at 10400
 * baud (~0.96ms/byte), so bytes are picked up promptly without spinning. */
#define PORT220_POLL_MS 1.0f

#define PORT220_DEFAULT_HOST "127.0.0.1"
#define PORT220_DEFAULT_PORT 6403

CSerialPort220::CSerialPort220(Bitu id, CommandLine *cmd)
    : CSerial(id, cmd), sock(-1), connected(false)
{
    CSerial::Init_Registers();

    std::string host = PORT220_DEFAULT_HOST;
    Bitu port = PORT220_DEFAULT_PORT;

    /* getBituSubstring/getStringSubstring are the base class's parsers for
     * "key:value" pairs, the same syntax the other backends use. */
    std::string tmp;
    if (cmd->FindStringBegin("host:", tmp, false) && !tmp.empty())
        host = tmp;
    (void)getBituSubstring("port:", &port, cmd);

    if (!openSocket(host.c_str(), (int)port)) {
        /* Leaving InstallationSuccessful false makes serialport.cpp delete
         * this object and leave the port absent, which is the honest outcome:
         * better than a port that silently swallows everything. */
        LOG_MSG("Port220: could not connect to %s:%d -- is the bridge running?",
                host.c_str(), (int)port);
        InstallationSuccessful = false;
        return;
    }

    /* The far end is a USB serial adapter, not a modem. Assert the lines the
     * guest looks at so software waiting on CTS/DSR/CD proceeds, and never
     * change them again -- there is no real modem here to reflect. */
    setCTS(true);
    setDSR(true);
    setCD(true);
    setRI(false);

    LOG_MSG("Port220: connected to %s:%d", host.c_str(), (int)port);
    InstallationSuccessful = true;
    setEvent(SERIAL_PORT220_POLL_EVENT, PORT220_POLL_MS);
}

CSerialPort220::~CSerialPort220()
{
    removeEvent(SERIAL_PORT220_POLL_EVENT);
    removeEvent(SERIAL_TX_EVENT);
    removeEvent(SERIAL_THR_EVENT);
    closeSocket();
}

bool CSerialPort220::openSocket(const char *host, int port)
{
    sock = ::socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        LOG_MSG("Port220: socket() failed: %s", strerror(errno));
        return false;
    }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    if (inet_pton(AF_INET, host, &addr.sin_addr) != 1) {
        LOG_MSG("Port220: bad host address '%s'", host);
        closeSocket();
        return false;
    }

    /* Blocking connect: it is loopback, so it either succeeds at once or the
     * bridge is not listening. A non-blocking connect would only add states
     * to get wrong. */
    if (::connect(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        LOG_MSG("Port220: connect() failed: %s", strerror(errno));
        closeSocket();
        return false;
    }

    /* Nagle would coalesce small writes and add up to 40ms before a byte
     * leaves. On a request/response protocol with a latency budget that is
     * exactly the wrong trade. */
    int one = 1;
    ::setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, (const char *)&one, sizeof(one));

    /* Non-blocking reads: the poll event must never stall the emulator. */
    int flags = ::fcntl(sock, F_GETFL, 0);
    if (flags >= 0) ::fcntl(sock, F_SETFL, flags | O_NONBLOCK);

    connected = true;
    return true;
}

void CSerialPort220::closeSocket()
{
    if (sock >= 0) {
        ::close(sock);
        sock = -1;
    }
    connected = false;
}

/* Drain whatever the bridge has for us and hand it to the UART.
 *
 * Bounded per call: the guest's receive path can only take one byte at a
 * time, and CanReceiveByte() going false means its FIFO is full. Bytes left
 * in the socket stay there until the next poll, which is the correct
 * back-pressure -- reading them into a buffer we own would just move the
 * overflow somewhere with no flow control. */
void CSerialPort220::pollIncoming()
{
    if (!connected) return;

    uint8_t buf[64];
    while (CanReceiveByte()) {
        const ssize_t n = ::recv(sock, buf, 1, 0);
        if (n == 1) {
            receiveByte(buf[0]);
            continue;
        }
        if (n == 0) {
            LOG_MSG("Port220: bridge closed the connection");
            closeSocket();
            return;
        }
        /* n < 0 */
        if (errno == EAGAIN || errno == EWOULDBLOCK) return;  /* nothing waiting */
        if (errno == EINTR) continue;
        LOG_MSG("Port220: recv() failed: %s", strerror(errno));
        closeSocket();
        return;
    }
}

void CSerialPort220::handleUpperEvent(uint16_t type)
{
    switch (type) {
    case SERIAL_PORT220_POLL_EVENT:
        pollIncoming();
        /* Re-arm unconditionally while connected. If the socket dropped,
         * stop polling rather than spin on a dead descriptor. */
        if (connected) setEvent(SERIAL_PORT220_POLL_EVENT, PORT220_POLL_MS);
        break;

    case SERIAL_THR_EVENT:
        ByteTransmitting();
        setEvent(SERIAL_TX_EVENT, bytetime);
        break;

    case SERIAL_TX_EVENT:
        ByteTransmitted();
        break;

    default:
        break;
    }
}

void CSerialPort220::transmitByte(uint8_t val, bool first)
{
    if (connected) {
        /* Blocking send on a loopback socket to a reader that drains
         * continuously. A short write is possible in principle; loop so a
         * byte is never quietly dropped. */
        size_t off = 0;
        const uint8_t b = val;
        while (off < 1) {
            const ssize_t n = ::send(sock, &b + off, 1 - off, 0);
            if (n > 0) { off += (size_t)n; continue; }
            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR))
                continue;
            LOG_MSG("Port220: send() failed: %s", strerror(errno));
            closeSocket();
            break;
        }
    }

    /* Timing bookkeeping the UART expects, same as every other backend. */
    if (first) setEvent(SERIAL_THR_EVENT, bytetime / 10);
    else       setEvent(SERIAL_TX_EVENT, bytetime);
}

/* The emulated program setting baud/parity/stop bits does not reach the
 * FT232R: the Kotlin bridge owns the real line settings (10400 8N1). Logged
 * because a mismatch between what EMSAN1 asks for and what the bridge is set
 * to is worth being able to see in a log. */
void CSerialPort220::updatePortConfig(uint16_t divider, uint8_t lcr)
{
    (void)lcr;
    if (divider) {
        const int baud = 115200 / (int)divider;
        LOG_MSG("Port220: guest requested %d baud (bridge is fixed at its own rate)", baud);
    }
}

void CSerialPort220::updateMSR()   { /* lines are static; nothing to sample */ }
void CSerialPort220::setBreak(bool value) { (void)value; }

/* Handshake lines are accepted and ignored. Sending them would put bytes on
 * the wire that EMSAN1's protocol does not expect. */
void CSerialPort220::setRTSDTR(bool rts, bool dtr) { (void)rts; (void)dtr; }
void CSerialPort220::setRTS(bool val) { (void)val; }
void CSerialPort220::setDTR(bool val) { (void)val; }
