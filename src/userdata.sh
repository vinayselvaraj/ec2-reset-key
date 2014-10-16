#!/bin/bash

TYPE="__TYPE__"
SSH_KEY="__SSH_KEY__"

# Wait 90 secs for EBS volume to be attached
x=0
while [ "$x" -lt 90 -a ! -e /dev/xvdh -a ! -e /dev/sdh ]
do
 x=$((x+1))
 sleep 1
done


if [ -e /dev/xvdh ]
then
  DEVICE=/dev/xvdh
elif [ -e /dev/sdh ]
then
  DEVICE=/dev/sdh
else
  echo "UNABLE TO FIND /dev/sdh OR /dev/xvdh"
  halt
fi

if [ $TYPE = "windows" ]
then
  
  # Install dependencies
  apt-get install chntpw
  
  # Mount disk and change password then umount
  mount -t ntfs "$DEVICE"2 /mnt
  cd /mnt/Windows/System32/config
  echo "chntpw -u Guest SAM <<EOF" >> /tmp/run_chntpw.sh
  echo "4" >> /tmp/run_chntpw.sh
  echo "y" >> /tmp/run_chntpw.sh
  echo "EOF" >> /tmp/run_chntpw.sh
  echo "chntpw -u Guest SAM <<EOF" >> /tmp/run_chntpw.sh
  echo "3" >> /tmp/run_chntpw.sh
  echo "y" >> /tmp/run_chntpw.sh
  echo "y" >> /tmp/run_chntpw.sh
  echo "EOF" >> /tmp/run_chntpw.sh
  sh /tmp/run_chntpw.sh   
fi

if [ $TYPE = "linux" ]
then
  
  if [ -e "$DEVICE"1 ]
  then
    mount "$DEVICE"1 /mnt
  else
    mount $DEVICE /mnt
  fi
  
  PATHS="/mnt/root /mnt/home/ec2-user /mnt/home/ubuntu"
  for i in $PATHS
  do
    if [ -e $i ]
    then
      if [ -e "$i"/.ssh -a -e "$i"/.ssh/authorized_keys ]
      then
        mv "$i"/.ssh/authorized_keys "$i"/.ssh/authorized_keys.old >& /dev/null
      else
        mkdir -p "$i"/.ssh
        chmod 700 "$i"/.ssh
      fi
      
      echo $SSH_KEY > "$i"/.ssh/authorized_keys
      chmod 600 "$i"/.ssh/authorized_keys
      
      USER=`ls -al $i | tail -1 | awk {'print $3'}`
      GROUP=`ls -al $i |tail -1 | awk {'print $4'}`
      chown -R $USER:$GROUP "$i"/.ssh
    fi
  done
fi

umount /mnt
halt
